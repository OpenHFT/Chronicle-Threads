/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.threads.Threads.shutdown;
import static net.openhft.chronicle.threads.Threads.unpark;

/**
 * Event Loop for blocking tasks.
 *
 * @author Peter Lawrey
 */
public class BlockingEventLoop implements EventLoop {

    private static final Logger LOG = LoggerFactory.getLogger(BlockingEventLoop.class);

    @NotNull
    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    @NotNull
    private final String name;
    private AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean started;
    private final List<EventHandler> handlers = new ArrayList<>();
    private NamedThreadFactory threadFactory;

    public BlockingEventLoop(@NotNull EventLoop parent,
                             @NotNull String name) {
        this.name = name;
        this.parent = parent;
        this.threadFactory = new NamedThreadFactory(name, true);
        this.service = Executors.newCachedThreadPool(threadFactory);


    }

    public BlockingEventLoop(@NotNull String name) {
        this.name = name;
        this.parent = this;
        this.threadFactory = new NamedThreadFactory(name, true);
        this.service = Executors.newCachedThreadPool(threadFactory);
    }

    @Override
    public void awaitTermination() {
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
     * @param handler to execute
     */
    @Override
    public synchronized void addHandler(@NotNull EventHandler handler) {
        if (DEBUG_ADDING_HANDLERS)
            System.out.println("Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");
        this.handlers.add(handler);
        if (started)
            this.startHandler(handler);
    }

    private String asString(Object handler) {
        return Integer.toHexString(System.identityHashCode(handler));
    }

    @Override
    public synchronized void start() {
        this.started = true;
        handlers.forEach(this::startHandler);
    }

    @NotNull
    private void startHandler(EventHandler handler) {
        try {
            service.submit(new Runner(handler));
        } catch (RejectedExecutionException e) {
            Jvm.warn().on(getClass(), e);
        }
    }

    @Override
    public void unpause() {
        unpark(service);
    }

    @Override
    public void stop() {

    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isAlive() {
        return !service.isShutdown();
    }

    @Override
    public synchronized void close() {
        if (closed.getAndSet(true))
            return;

        threadFactory.threads().forEach(Thread::interrupt);
 
        Threads.shutdown(service);
        if (! started)
            handlers.forEach(EventHandler::loopFinished);
        closeQuietly(handlers);
    }

    @Override
    public String toString() {
        return "BlockingEventLoop{" +
                "name=" + name +
                '}';
    }

    private class Runner implements Runnable {
        private final EventHandler handler;

        public Runner(EventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void run() {
            handler.eventLoop(parent);
            try {
                while (!closed.get())
                    handler.action();



            } catch (InvalidEventHandlerException e) {
                // expected and logged below.
//              TODO:   handlersRemoved.add(handler);
            } catch (Throwable t) {
                if (!closed.get())
                    Jvm.warn().on(handler.getClass(), asString(handler) + " threw", t);

            } finally {
                if (LOG.isDebugEnabled())
                    Jvm.debug().on(handler.getClass(), "handler " + asString(handler) + " done.");
                handler.loopFinished();
            }
        }
    }
}
