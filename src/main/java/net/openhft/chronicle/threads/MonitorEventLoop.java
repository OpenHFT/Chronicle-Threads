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
import net.openhft.chronicle.core.annotation.HotMethod;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/*
 * Created by peter.lawrey on 22/01/15.
 */
public class MonitorEventLoop implements EventLoop, Runnable, Closeable {
    public static final String MONITOR_INITIAL_DELAY = "MonitorInitialDelay";
    static int MONITOR_INITIAL_DELAY_MS = Integer.getInteger(MONITOR_INITIAL_DELAY, 60_000);
    final ExecutorService service;
    private final EventLoop parent;
    private final List<EventHandler> handlers = new ArrayList<>();
    private final Pauser pauser;
    private volatile boolean running = true;

    public MonitorEventLoop(EventLoop parent, Pauser pauser) {
        this(parent, "", pauser);
    }

    public MonitorEventLoop(EventLoop parent, String name, Pauser pauser) {
        this.parent = parent;
        this.pauser = pauser;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name + "event-loop-monitor", true));
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
    public boolean isClosed() {
        return !service.isShutdown();
    }

    @Override
    public boolean isAlive() {
        return running;
    }

    @Override
    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        addHandler(handler);
    }

    @Override
    public void addHandler(@NotNull EventHandler handler) {
        synchronized (handlers) {
            if (!handlers.contains(handler))
                handlers.add(handler);
            handler.eventLoop(parent);
        }
    }

    @Override
    @HotMethod
    public void run() {
        try {
            // don't do any monitoring for the first 60000 ms.
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
            EventHandler handler = handlers.get(i);
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
    public void close() {
        stop();
        Threads.shutdownDaemon(service);
    }
}
