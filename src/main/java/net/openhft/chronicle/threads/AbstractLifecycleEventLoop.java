/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
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
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A parent class that:
 * <ul>
 *     <li>Enforces the life-cycle of an EventLoop</li>
 *     <li>Implements idempotency for {@link #start()}, {@link #stop()}</li>
 *     <li>Ensures {@link #stop()} only returns when the EventLoop is stopped</li>
 * </ul>
 * See {@link EventLoopLifecycle} for details of the life-cycle
 */
public abstract class AbstractLifecycleEventLoop extends AbstractCloseable implements EventLoop {

    /**
     * After this time, awaitTermination will log an error and return, this is really only so
     * tests don't block forever. This time should be kept as "effectively forever".
     */
    private static final long AWAIT_TERMINATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);
    protected final String name;

    protected AbstractLifecycleEventLoop(@NotNull String name) {
        this.name = name.replaceAll("/$", "");

        singleThreadedCheckDisabled(true);
    }

    protected String nameWithSlash() {
        return withSlash(name);
    }

    @Override
    public final void start() {
        throwExceptionIfClosed();

        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STARTED)) {
            try {
                performStart();
            } catch (Exception ex) {
                stop();
                Jvm.rethrow(ex);
            }
        }
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * Implement whatever this event loop needs to start, will only
     * ever be called once
     */
    protected abstract void performStart();

    @Override
    public final void stop() {
        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STOPPING)) {
            performStopFromNew();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else if (lifecycle.compareAndSet(EventLoopLifecycle.STARTED, EventLoopLifecycle.STOPPING)) {
            performStopFromStarted();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else {
            awaitTermination();
        }
    }

    /**
     * Implement a stop from {@link EventLoopLifecycle#NEW} state, should block until all
     * handlers have had {@link EventHandler#loopFinished()} called.
     */
    protected abstract void performStopFromNew();

    /**
     * Implement a stop from {@link EventLoopLifecycle#STARTED} state, should block until all
     * handlers have completed their final iteration and had
     * {@link EventHandler#loopFinished()} called.
     */
    protected abstract void performStopFromStarted();

    @Override
    public final void awaitTermination() {
        long endTime = System.currentTimeMillis() + AWAIT_TERMINATION_TIMEOUT_MS;
        while (!Thread.currentThread().isInterrupted()) {
            if (lifecycle.get() == EventLoopLifecycle.STOPPED)
                return;
            if (System.currentTimeMillis() > endTime) {
                Jvm.error().on(getClass(), "awaitTermination() timed out, continuing. This probably represents a bug.");
            }
            Jvm.pause(1);
        }
        if (lifecycle.get() != EventLoopLifecycle.STOPPED) {
            Jvm.warn().on(getClass(), "awaitTermination() interrupted, returning in state " + lifecycle.get());
        }
    }

    @Override
    protected void performClose() {
        stop();
    }

    protected boolean isStarted() {
        return lifecycle.get() == EventLoopLifecycle.STARTED;
    }

    @Override
    public boolean isStopped() {
        return lifecycle.get() == EventLoopLifecycle.STOPPED;
    }

    static String withSlash(String n) {
        return n.isEmpty() ? n : n + "/";
    }
}
