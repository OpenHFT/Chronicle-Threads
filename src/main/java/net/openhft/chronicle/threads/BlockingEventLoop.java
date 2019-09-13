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

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

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
    private volatile boolean closed;
    private volatile boolean started;
    private List<EventHandler> handlers = new ArrayList<>();

    public BlockingEventLoop(@NotNull EventLoop parent,
                             @NotNull String name) {
        this.parent = parent;
        this.service = Executors.newCachedThreadPool(new NamedThreadFactory(name, true));
    }

    public BlockingEventLoop(@NotNull String name) {
        this.parent = this;
        this.service = Executors.newCachedThreadPool(new NamedThreadFactory(name, true));
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
    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        addHandler(handler);
    }

    /**
     * This can be called multiple times and each handler will be executed in its own thread
     * @param handler to execute
     */
    @Override
    public void addHandler(@NotNull EventHandler handler) {
        this.handlers.add(handler);
        if (started)
            this.startHandler(handler);
    }

    private String asString(Object handler) {
        return Integer.toHexString(System.identityHashCode(handler));
    }

    @Override
    public void start() {
        this.started = true;
        try {
            handlers.forEach(this::startHandler);
        } catch (RejectedExecutionException e) {
            if (!closed)
                Jvm.warn().on(getClass(), e);
        }
    }

    @NotNull
    private void startHandler(EventHandler handler) {
        service.submit(new Runner(handler));
    }

    @Override
    public void unpause() {

    }

    @Override
    public void stop() {

    }

    @Override
    public boolean isClosed() {
        return service.isShutdown();
    }

    @Override
    public boolean isAlive() {
        return !service.isShutdown();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(handlers);
        Threads.shutdown(service);
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
                while (!closed)
                    handler.action();

            } catch (InvalidEventHandlerException e) {
                // expected and logged below.

            } catch (Throwable t) {
                if (!closed)
                    Jvm.warn().on(handler.getClass(), asString(handler) + " threw", t);

            } finally {
                if (LOG.isDebugEnabled())
                    Jvm.debug().on(handler.getClass(), "handler " + asString(handler) + " done.");
                if (closed)
                    EventHandler.closeHandler(handler);
            }
        }
    }
}
