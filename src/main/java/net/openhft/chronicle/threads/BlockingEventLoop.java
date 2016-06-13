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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * Event "Loop" for blocking tasks. Created by peter.lawrey on 26/01/15.
 */
public class BlockingEventLoop implements EventLoop {

    private static final Logger LOG = LoggerFactory.getLogger(BlockingEventLoop.class);

    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    @Nullable
    private Thread thread = null;
    private volatile boolean closed;
    private EventHandler handler;

    public BlockingEventLoop(@NotNull EventLoop parent,
                             @NotNull String name) {
        this.parent = parent;
        this.service = Executors.newCachedThreadPool(new NamedThreadFactory(name, true));
    }

    @Override
    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        addHandler(handler);
    }

    @Override
    public void addHandler(@NotNull EventHandler handler) {
        this.handler = handler;
        try {
            service.submit(() -> {
                thread = Thread.currentThread();
                handler.eventLoop(parent);
                try {
                    while (!closed)
                        handler.action();

                } catch (InvalidEventHandlerException e) {
                    Jvm.debug().on(getClass(), e);

                } catch (Throwable t) {
                    Jvm.warn().on(getClass(), t);

                } finally {
                    if (LOG.isDebugEnabled())
                        Jvm.debug().on(getClass(), "handler " + handler + " done.");
                    if (closed)
                        closeQuietly(this.handler);
                }
            });
        } catch (RejectedExecutionException e) {
            if (!closed)
                Jvm.warn().on(getClass(), e);
        }
    }

    @Override
    public void start() {
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
        closeQuietly(this.handler);
        Threads.shutdown(service);

    }

}
