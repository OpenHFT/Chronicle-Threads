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
import net.openhft.chronicle.core.annotation.HotMethod;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.SimpleCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class MonitorEventLoop extends SimpleCloseable implements EventLoop, Runnable {
    public static final String MONITOR_INITIAL_DELAY = "MonitorInitialDelay";
    static int MONITOR_INITIAL_DELAY_MS = Integer.getInteger(MONITOR_INITIAL_DELAY, 10_000);

    private transient final ExecutorService service;
    private final EventLoop parent;
    private final List<EventHandler> handlers = new ArrayList<>();
    private final Pauser pauser;
    private final String name;

    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);

    public MonitorEventLoop(final EventLoop parent, final Pauser pauser) {
        this(parent, "", pauser);
    }

    public MonitorEventLoop(final EventLoop parent, final String name, final Pauser pauser) {
        this.parent = parent;
        this.pauser = pauser;
        this.name = name + (parent == null ? "" : parent.name()) + "/event~loop~monitor";
        service = Executors.newSingleThreadExecutor(
                new NamedThreadFactory(name, true, null, true));
    }

    public String name() {
        return name;
    }

    @Override
    public void awaitTermination() {
        while (!Thread.currentThread().isInterrupted()) {
            if (lifecycle.get() == EventLoopLifecycle.STOPPED) {
                return;
            }
            Jvm.pause(1);
        }
    }

    @Override
    public void start() {
        throwExceptionIfClosed();

        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STARTED)) {
            service.submit(this);
        }
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    @Override
    public void stop() {
        if (lifecycle.compareAndSet(EventLoopLifecycle.STARTED, EventLoopLifecycle.STOPPING)
                || lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STOPPING)) {
            unpause();
            Threads.shutdownDaemon(service);
            handlers.forEach(Threads::loopFinishedQuietly);
            lifecycle.set(EventLoopLifecycle.STOPPED);
        }
        awaitTermination();
    }

    @Override
    public boolean isAlive() {
        return lifecycle.get() == EventLoopLifecycle.STARTED;
    }

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");
        synchronized (handlers) {
            if (!handlers.contains(handler))
                handlers.add(new IdempotentLoopStartedEventHandler(handler));
            handler.eventLoop(parent);
        }
    }

    @Override
    @HotMethod
    public void run() {
        throwExceptionIfClosed();

        try {
            // don't do any monitoring for the first MONITOR_INITIAL_DELAY_MS ms
            final long waitUntilMs = System.currentTimeMillis() + MONITOR_INITIAL_DELAY_MS;
            while (System.currentTimeMillis() < waitUntilMs && lifecycle.get() == EventLoopLifecycle.STARTED)
                pauser.pause();
            pauser.reset();
            while (lifecycle.get() == EventLoopLifecycle.STARTED && !Thread.currentThread().isInterrupted()) {
                boolean busy;
                synchronized (handlers) {
                    busy = runHandlers();
                }
                pauser.pause();
                if (busy)
                    pauser.reset();
            }
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), e);
        }
    }

    @HotMethod
    private boolean runHandlers() {
        boolean busy = false;
        // assumed to be synchronized in run()
        for (int i = 0; i < handlers.size(); i++) {
            final EventHandler handler = handlers.get(i);
            handler.loopStarted();
            try {
                busy |= handler.action();

            } catch (InvalidEventHandlerException e) {
                removeHandler(i--);
            } catch (Exception e) {
                Jvm.warn().on(getClass(), "Loop terminated due to exception", e);
                removeHandler(i--);
            }
        }
        return busy;
    }

    private void removeHandler(int handlerIndex) {
        try {
            EventHandler removedHandler = handlers.remove(handlerIndex);
            removedHandler.loopFinished();
            Closeable.closeQuietly(removedHandler);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (!handlers.isEmpty()) {
                Jvm.warn().on(MonitorEventLoop.class, "Error removing handler!");
            }
        }
    }

    @Override
    protected void performClose() {
        super.performClose();

        stop();
        net.openhft.chronicle.core.io.Closeable.closeQuietly(handlers);
    }
}