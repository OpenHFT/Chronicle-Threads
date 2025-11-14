/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base implementation that manages the life-cycle of an {@link EventLoop}.
 *
 * <p>It extends {@link AbstractCloseable}, and integrates with the
 * closeable hierarchy.</p>
 *
 * <p>The life-cycle follows {@link EventLoopLifecycle}:</p>
 * <ul>
 *     <li>{@code NEW} &ndash; constructed but not running.</li>
 *     <li>{@code STARTED} &ndash; handlers are executing.</li>
 *     <li>{@code STOPPING} &ndash; {@link #stop()} has been requested.</li>
 *     <li>{@code STOPPED} &ndash; all work is finished.</li>
 * </ul>
 * Transitions are linear in that order. Invoking {@code stop()} while in
 * {@code NEW} skips {@code STARTED} entirely. Both {@code start()} and
 * {@code stop()} are idempotent and {@code stop()} blocks until the loop is
 * {@code STOPPED}.
 */
@SuppressWarnings("this-escape")
public abstract class AbstractLifecycleEventLoop extends AbstractCloseable implements EventLoop {

    /**
     * After this time, awaitTermination will log an error and return, this is really only so
     * tests don't block forever. This time should be kept as "effectively forever".
     */
    private static final long AWAIT_TERMINATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);
    protected final String name;
    private final String nameWithSlash;
    boolean privateGroup;

    /**
     * Create an instance with the supplied name.
     * <p>
     * The {@link AbstractCloseable} thread ownership check is disabled so the
     * loop may be started or stopped from threads other than the creating
     * thread.
     *
     * @param name descriptive name for the loop
     */
    protected AbstractLifecycleEventLoop(@NotNull String name) {
        this.name = name.replaceAll("/$", "");
        this.nameWithSlash = withSlash(this.name);

        // event loops operate on dedicated threads but may be closed elsewhere
        singleThreadedCheckDisabled(true);
    }

    protected final String nameWithSlash() {
        return nameWithSlash;
    }

    @Override
    public final void start() {
        throwExceptionIfClosed();

        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STARTED)) {
            performStart();
        }
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * Perform the concrete start up work.
     * Invoked exactly once when the life-cycle moves from
     * {@link EventLoopLifecycle#NEW} to {@link EventLoopLifecycle#STARTED}.
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
     * Stop the loop when {@link #stop()} is invoked before it has started.
     * Implementations should block until every handler has received
     * {@link EventHandler#loopFinished()}.
     */
    protected abstract void performStopFromNew();

    /**
     * Stop the loop once it has begun processing.
     * Implementations should wait for the current iteration to finish and then
     * invoke {@link EventHandler#loopFinished()} on every handler.
     */
    protected abstract void performStopFromStarted();

    /**
     * Wait for the loop to reach {@link EventLoopLifecycle#STOPPED}.
     *
     * <p>If the state does not change within
     * {@link #AWAIT_TERMINATION_TIMEOUT_MS} milliseconds an error is logged and
     * the method returns. The timeout is primarily to avoid tests hanging
     * indefinitely.</p>
     */
    protected final void awaitTermination() {
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

    @Override
    protected void assertCloseable() {
        if (!privateGroup && isRunningOnThread(Thread.currentThread())) {
            throw new ThreadingIllegalStateException(getClass() + ": Attempting to close " + name + " from within!", createdHere());
        }
    }

    public abstract boolean isRunningOnThread(Thread thread);

    protected boolean isStarted() {
        return lifecycle.get() == EventLoopLifecycle.STARTED;
    }

    @Override
    public boolean isStopped() {
        return lifecycle.get().isStopped();
    }

    static String withSlash(String n) {
        return n.isEmpty() ? n : n + "/";
    }

    public void privateGroup(boolean privateGroup) {
        this.privateGroup = privateGroup;
    }
}
