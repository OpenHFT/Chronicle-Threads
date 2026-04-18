/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Pauser that waits a fixed number of milliseconds.
 * <p>
 * The implementation parks the thread with {@link LockSupport#parkNanos(long)}
 * so CPU usage stays low.  The delay is configured via {@link #pauseTimeMS(long)}
 * and can be limited with {@link #minPauseTimeMS(long)}.
 */
public class MilliPauser implements Pauser {
    private final AtomicBoolean pausing = new AtomicBoolean();
    private long pauseTimeMS;
    private long timePaused = 0;
    private long countPaused = 0;
    private long pauseUntilMS = 0;
    @Nullable
    private transient volatile Thread thread = null;

    /**
     * Constructs a new {@code MilliPauser} with a specified pause time in milliseconds.
     *
     * @param pauseTimeMS the pause time for each pause operation, in milliseconds
     */
    public MilliPauser(long pauseTimeMS) {
        this.pauseTimeMS = pauseTimeMS;
    }

    /**
     * Sets the delay for future pauses.
     *
     * @param pauseTimeMS pause duration in milliseconds
     * @return this instance for chaining
     */
    public MilliPauser pauseTimeMS(long pauseTimeMS) {
        this.pauseTimeMS = pauseTimeMS;
        return this;
    }

    /**
     * Reduces the delay if the supplied value is lower.
     * Always enforces a minimum of one millisecond.
     *
     * @param pauseTimeMS proposed minimum pause in milliseconds
     * @return this instance for chaining
     */
    public MilliPauser minPauseTimeMS(long pauseTimeMS) {
        this.pauseTimeMS = Math.min(this.pauseTimeMS, pauseTimeMS);
        if (this.pauseTimeMS < 1)
            this.pauseTimeMS = 1;
        return this;
    }

    /**
     * Retrieves the current pause time in milliseconds.
     *
     * @return the pause time in milliseconds
     */
    public long pauseTimeMS() {
        return pauseTimeMS;
    }

    @Override
    public void reset() {
        pauseUntilMS = 0;
    }

    /**
     * Pauses the current thread for the configured duration using millisecond precision.
     */
    @Override
    public void pause() {
        doPauseMS(pauseTimeMS);
    }

    /**
     * Start an asynchronous pause for the configured delay.
     * The call returns at once and {@link #asyncPausing()} can be polled.
     */
    @Override
    public void asyncPause() {
        pauseUntilMS = System.currentTimeMillis() + pauseTimeMS;
    }

    /**
     * Test whether the asynchronous pause has expired.
     *
     * @return {@code true} while the pause should continue
     */
    @Override
    public boolean asyncPausing() {
        return pauseUntilMS > System.currentTimeMillis();
    }

    /**
     * Pauses the current thread for a specified duration in milliseconds.
     *
     * @param timeout  the maximum time to pause in the specified {@code timeUnit}
     * @param timeUnit the unit of time for {@code timeout}
     * @throws TimeoutException if the pause operation is not completed within the specified timeout
     */
    @Override
    public void pause(long timeout, @NotNull TimeUnit timeUnit) throws TimeoutException {
        doPauseMS(timeUnit.toMillis(timeout));
    }

    /**
     * Perform the pause for the given delay.
     * Uses {@link LockSupport#parkNanos(long)} so the CPU stays mostly idle.
     *
     * @param delayMS delay in milliseconds
     */
    void doPauseMS(long delayMS) {
        long start = System.nanoTime();
        thread = Thread.currentThread();
        pausing.set(true);
        if (!thread.isInterrupted())
            LockSupport.parkNanos(delayMS * 1_000_000L);
        pausing.set(false);
        long time = System.nanoTime() - start;
        timePaused += time;
        countPaused++;
    }

    /**
     * Unpauses the currently paused thread if it is in a paused state.
     */
    @Override
    public void unpause() {
        final Thread threadSnapshot = this.thread;
        if (threadSnapshot != null && pausing.get())
            LockSupport.unpark(threadSnapshot);
    }

    /**
     * Returns the total time that the thread has been paused, measured in milliseconds.
     *
     * @return the total paused time in milliseconds
     */
    @Override
    public long timePaused() {
        return timePaused / 1_000_000;
    }

    /**
     * Returns the number of times this pauser has been activated to pause the thread.
     *
     * @return the total count of pauses
     */
    @Override
    public long countPaused() {
        return countPaused;
    }

    /**
     * Provides a string representation of this pauser, identifying the configured pause time.
     *
     * @return a string representation of this {@code MilliPauser}
     */
    @Override
    public String toString() {
        if (pauseTimeMS == 1)
            return "PauserMode.milli";
        return "Pauser.millis(" + pauseTimeMS + ')';
    }
}
