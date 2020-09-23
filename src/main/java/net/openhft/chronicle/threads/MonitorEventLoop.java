/*
 * Copyright 2016-2020 Chronicle Software
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
import net.openhft.chronicle.core.io.SimpleCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MonitorEventLoop extends SimpleCloseable implements EventLoop, Runnable {
    public static final String MONITOR_INITIAL_DELAY = "MonitorInitialDelay";
    private static int MONITOR_INITIAL_DELAY_MS = Integer.getInteger(MONITOR_INITIAL_DELAY, 10_000);

    private final ExecutorService service;
    private final EventLoop parent;
    private final List<EventHandler> handlers = new ArrayList<>();
    private final Pauser pauser;
    private final String name;

    private volatile boolean running = true;

    public MonitorEventLoop(final EventLoop parent, final Pauser pauser) {
        this(parent, "", pauser);
    }

    public MonitorEventLoop(final EventLoop parent, final String name, final Pauser pauser) {
        this.parent = parent;
        this.pauser = pauser;
        this.name = name + (parent == null ? "" : parent.name()) + "/event~loop~monitor";
        service = Executors.newSingleThreadExecutor(
                new NamedThreadFactory(name, true));
    }

    public String name() {
        return name;
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
    public void start() {
        throwExceptionIfClosed();

        running = true;
        service.submit(this);
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isAlive() {
        return running;
    }

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        if (DEBUG_ADDING_HANDLERS)
            System.out.println("Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");
        synchronized (handlers) {
            if (!handlers.contains(handler))
                handlers.add(handler);
            handler.eventLoop(parent);
            handler.loopStarted();
        }
    }

    @Override
    @HotMethod
    public void run() {
        throwExceptionIfClosed();

        try {
            // don't do any monitoring for the first 10000 ms.
            for (int i = 0; i < MONITOR_INITIAL_DELAY_MS; i += 50)
                if (running)
                    Jvm.pause(50);
            while (running && !Thread.currentThread().isInterrupted()) {
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
            // TODO shouldn't need this.
            if (handler == null) continue;
            try {
                busy |= handler.action();

            } catch (InvalidEventHandlerException e) {
                handlers.remove(i--);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), "Loop terminated due to exception", e);
            }
        }
        return busy;
    }

    @Override
    protected void performClose() {
        super.performClose();

        stop();
        Threads.shutdownDaemon(service);
        handlers.forEach(Threads::loopFinishedQuietly);
        net.openhft.chronicle.core.io.Closeable.closeQuietly(handlers);
    }
}