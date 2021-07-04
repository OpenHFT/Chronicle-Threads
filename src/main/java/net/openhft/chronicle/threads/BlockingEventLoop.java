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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;
import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.threads.Threads.loopFinishedQuietly;
import static net.openhft.chronicle.threads.Threads.unpark;

/**
 * Event Loop for blocking tasks.
 */
public class BlockingEventLoop extends SimpleCloseable implements EventLoop {

    private enum Lifecycle {
        NEW,
        STARTED,
        STOPPED
    }

    @NotNull
    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    @NotNull
    private final String name;
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);
    private final List<EventHandler> handlers = new ArrayList<>();
    private final NamedThreadFactory threadFactory;
    private final Pauser pauser = Pauser.balanced();

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
        assert lifecycle.get() == Lifecycle.STOPPED;
        try {
            service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        if (lifecycle.get() == Lifecycle.STOPPED)
            throw new IllegalStateException("Event Group has been closed");
        this.handlers.add(handler);
        if (lifecycle.get() == Lifecycle.STARTED)
            this.startHandler(handler);
    }

    private String asString(final Object handler) {
        return Integer.toHexString(System.identityHashCode(handler));
    }

    @Override
    public synchronized void start() {
        if (!lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.STARTED)) {
            throw new IllegalStateException(format("Attempted to start event loop in state %s", lifecycle.get()));
        }
        handlers.forEach(this::startHandler);
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
        unpark(service);
    }

    @Override
    public void stop() {
        if (lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.STOPPED)) {
            // We never started, we should call loopFinished
            handlers.forEach(EventHandler::loopFinished);
        } else if (lifecycle.compareAndSet(Lifecycle.STARTED, Lifecycle.STOPPED)) {
            // Do nothing
        } else {
            if (Jvm.isDebugEnabled(BlockingEventLoop.class))
                Jvm.debug().on(BlockingEventLoop.class, format("Attempted to stop event loop in state %s, ignoring", lifecycle.get()));
            return;
        }
        service.shutdownNow();
        unpause();
    }

    @Override
    public boolean isAlive() {
        return !service.isShutdown();
    }

    @Override
    protected void performClose() {
        super.performClose();
        stop();
        Threads.shutdown(service);
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

                handler.eventLoop(parent);
                handler.loopStarted();

                while (lifecycle.get() == Lifecycle.STARTED) {
                    if (handler.action())
                        pauser.reset();
                    else
                        pauser.pause();
                }

            } catch (InvalidEventHandlerException e) {
                // expected and logged below.
            } catch (Throwable t) {
                if (lifecycle.get() == Lifecycle.STARTED)
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