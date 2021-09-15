/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.SimpleCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.threads.Threads.loopFinishedQuietly;
import static net.openhft.chronicle.threads.Threads.unpark;

/**
 * Event Loop for blocking tasks.
 */
public class BlockingEventLoop extends SimpleCloseable implements EventLoop {

    @NotNull
    private final EventLoop parent;
    @NotNull
    private transient final ExecutorService service;
    @NotNull
    private final String name;
    private final List<EventHandler> handlers = new ArrayList<>();
    private final NamedThreadFactory threadFactory;
    private final Pauser pauser = Pauser.balanced();
    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);

    public BlockingEventLoop(@NotNull final EventLoop parent,
                             @NotNull final String name) {
        this.name = name;
        this.parent = parent;
        this.threadFactory = new NamedThreadFactory(name, null, null, true);
        this.service = Executors.newCachedThreadPool(threadFactory);
    }

    public BlockingEventLoop(@NotNull final String name) {
        this.name = name;
        this.parent = this;
        this.threadFactory = new NamedThreadFactory(name, null, null, true);
        this.service = Executors.newCachedThreadPool(threadFactory);
    }

    @Override
    public void awaitTermination() {
        while (!Thread.currentThread().isInterrupted()) {
            if (lifecycle.get() == EventLoopLifecycle.STOPPED)
                return;
            Jvm.pause(1);
        }
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * This can be called multiple times and each handler will be executed in its own thread
     *
     * @param handler to execute
     */
    @Override
    public synchronized void addHandler(@NotNull final EventHandler handler) {
        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");
        this.handlers.add(handler);
        handler.eventLoop(parent);
        if (EventLoopLifecycle.STARTED.equals(lifecycle.get()))
            this.startHandler(handler);
    }

    private String asString(final Object handler) {
        return Integer.toHexString(System.identityHashCode(handler));
    }

    @Override
    public synchronized void start() {
        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STARTED)) {
            handlers.forEach(this::startHandler);
        }
    }

    private void startHandler(final EventHandler handler) {
        try {
            service.submit(new Runner(handler));

        } catch (RejectedExecutionException e) {
            if (!service.isShutdown())
                Jvm.warn().on(getClass(), e);
        }
    }

    @Override
    public void unpause() {
        pauser.unpause();
        unpark(service);
    }

    @Override
    public synchronized void stop() {
        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STOPPING)) {
            shutdownExecutorService();
            handlers.forEach(Threads::loopFinishedQuietly);
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else if (lifecycle.compareAndSet(EventLoopLifecycle.STARTED, EventLoopLifecycle.STOPPING)) {
            shutdownExecutorService();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        }
        awaitTermination();
    }

    private void shutdownExecutorService() {
        /*
         * It's necessary for blocking handlers to be interrupted, so they abort what they're
         * doing and run to completion immediately.
         */
        service.shutdownNow();
        unpause();
        Threads.shutdown(service);
    }

    @Override
    public boolean isAlive() {
        return !service.isShutdown();
    }

    @Override
    protected void performClose() {
        super.performClose();

        stop();
        closeQuietly(handlers);
    }

    @Override
    public String toString() {
        return "BlockingEventLoop{" +
                "name=" + name +
                '}';
    }

    private final class Runner implements Runnable {
        private final EventHandler handler;

        public Runner(final EventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void run() {
            try {
                throwExceptionIfClosed();

                handler.loopStarted();

                while (lifecycle.get() == EventLoopLifecycle.STARTED) {
                    if (handler.action())
                        pauser.reset();
                    else
                        pauser.pause();
                }

            } catch (InvalidEventHandlerException e) {
                // expected and logged below.
            } catch (Throwable t) {
                if (!isClosed())
                    Jvm.warn().on(handler.getClass(), asString(handler) + " threw ", t);

            } finally {
                if (Jvm.isDebugEnabled(handler.getClass()))
                    Jvm.debug().on(handler.getClass(), "handler " + asString(handler) + " done.");
                loopFinishedQuietly(handler);
                // remove handler for clarity when debugging
                handlers.remove(handler);
                closeQuietly(handler);
            }
        }
    }
}