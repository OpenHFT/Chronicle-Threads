/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
 * {@code STOPPED}. A failed or interrupted termination wait throws without
 * claiming that shutdown completed.
 */
@SuppressWarnings("this-escape")
public abstract class AbstractLifecycleEventLoop extends AbstractCloseable implements EventLoop {

    /**
     * Bound a secondary caller's wait for the thread already stopping the loop.
     */
    private static final long AWAIT_TERMINATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);
    private final AtomicBoolean terminationFailureReported = new AtomicBoolean();
    private final long terminationTimeoutNs;
    private final LongSupplier nanoClock;
    private volatile Thread stoppingThread;
    private volatile Throwable stopFailure;
    protected final String name;
    volatile boolean privateGroup;

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
        this(name, TimeUnit.MILLISECONDS.toNanos(AWAIT_TERMINATION_TIMEOUT_MS), System::nanoTime);
    }

    // Package-local seam: tests advance elapsed time without changing the production deadline.
    AbstractLifecycleEventLoop(@NotNull String name, long terminationTimeoutNs, LongSupplier nanoClock) {
        if (terminationTimeoutNs <= 0)
            throw new IllegalArgumentException("Termination timeout must be positive");
        this.terminationTimeoutNs = terminationTimeoutNs;
        this.nanoClock = nanoClock;
        this.name = name.replaceAll("/$", "");

        // event loops operate on dedicated threads but may be closed elsewhere
        singleThreadedCheckDisabled(true);
    }

    protected String nameWithSlash() {
        return withSlash(name);
    }

    //! Callers need a lifecycle-specific rejection to avoid hiding genuine setup failures during shutdown.
    //! Wrap only this loop's close check; wrapping addHandler as a whole could relabel a callback failure.
    //! Regression: HandlerRegistrationClosedExceptionTest.closedLoopRejectsWithoutTakingOwnership
    //! and callbackFailureIsNotReclassifiedAsRejection.
    final void throwIfClosedForRegistration() {
        try {
            throwExceptionIfClosed();
        } catch (ClosedIllegalStateException closed) {
            throw new HandlerRegistrationClosedException(closed);
        }
    }

    /**
     * Registers a caller-owned handler, or reports lifecycle rejection without taking ownership.
     * A rejected handler receives no lifecycle callbacks from this call. The caller must arrange
     * its cleanup. Accepted handlers follow the loop's usual lifecycle; admission does not
     * guarantee that shutdown will leave time for an action to run.
     *
     * <p>Configuration and application callback failures retain their unchecked exceptions.
     * Do not submit a handler that is already owned by a loop. Custom subclasses must override
     * this operation to support checked admission; their existing {@code addHandler} is unchanged.</p>
     *
     * @throws HandlerRegistrationRejectedException if stopping or closure prevents admission
     * @throws UnsupportedOperationException if a custom loop has not implemented checked admission
     */
    //! An additive concrete method keeps existing third-party subclasses source/binary compatible.
    //! It must not delegate to a legacy method that could silently consume a rejected handler.
    //! Regression: HandlerAdmissionTest.customLoopMustExplicitlySupportCheckedAdmission.
    public void addHandlerOrThrow(@NotNull EventHandler handler) throws HandlerRegistrationRejectedException {
        throw new UnsupportedOperationException("Checked handler registration is not supported by " + getClass().getName());
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
            performStop(false);
        } else if (lifecycle.compareAndSet(EventLoopLifecycle.STARTED, EventLoopLifecycle.STOPPING)) {
            performStop(true);
        } else {
            awaitTermination();
        }
    }

    private void performStop(boolean started) {
        stoppingThread = Thread.currentThread();
        try {
            if (started)
                performStopFromStarted();
            else
                performStopFromNew();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } catch (RuntimeException | Error failure) {
            stopFailure = failure;
            throw failure;
        } finally {
            stoppingThread = null;
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
     * an {@link IllegalStateException} is thrown. Interruption preserves the
     * interrupted status and also fails the wait. Neither case completes the
     * lifecycle or transfers ownership of resources.</p>
     */
    protected final void awaitTermination() {
        long start = nanoClock.getAsLong();
        while (true) {
            if (lifecycle.get() == EventLoopLifecycle.STOPPED)
                return;
            long elapsed = nanoClock.getAsLong() - start;
            if (stopFailure != null)
                throw terminationFailure("stop callback failed", elapsed);
            if (stoppingThread == Thread.currentThread())
                throw terminationFailure("reentrant stop", elapsed);
            if (Thread.currentThread().isInterrupted())
                throw terminationFailure("interrupted", elapsed);
            if (elapsed >= terminationTimeoutNs)
                throw terminationFailure("timed out", elapsed);
            Jvm.pause(1);
        }
    }

    private IllegalStateException terminationFailure(String reason, long elapsedNs) {
        StringBuilder diagnostic = new StringBuilder("awaitTermination() ").append(reason)
                .append(": loop=").append(name).append(", lifecycle=").append(lifecycle.get())
                .append(", elapsedMs=").append(TimeUnit.NANOSECONDS.toMillis(elapsedNs));
        Thread stopper = stoppingThread;
        appendThread(diagnostic, "stopper", stopper);
        int remaining = 8;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != stopper && isRunningOnThread(thread)) {
                appendThread(diagnostic, "event loop", thread);
                if (--remaining == 0) {
                    diagnostic.append("\nFurther event-loop threads omitted");
                    break;
                }
            }
        }
        IllegalStateException failure = new IllegalStateException(diagnostic.toString(), stopFailure);
        // A timeout must not produce one error per millisecond, or per subsequent close call.
        if (terminationFailureReported.compareAndSet(false, true))
            Jvm.error().on(getClass(), diagnostic.toString(), failure);
        return failure;
    }

    @SuppressWarnings("deprecation") // Thread.threadId() is unavailable on the supported Java 8 baseline.
    private static void appendThread(StringBuilder diagnostic, String role, Thread thread) {
        diagnostic.append('\n').append(role).append('=');
        if (thread == null) {
            diagnostic.append("none");
            return;
        }
        diagnostic.append(thread.getName()).append(" id=").append(thread.getId())
                .append(" state=").append(thread.getState());
        StackTraceElement[] stack = thread.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 64); i++)
            diagnostic.append("\n  at ").append(stack[i]);
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
        // AbstractCloseable swallows performClose failures and marks the object closed.
        // Stop before entering that path so failure cannot release a live loop's handlers.
        stop();
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
