/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Busy-spin pauser that also implements {@link TimingPauser}.
 * <p>
 * Like {@link BusyPauser} it never yields or sleeps, so it occupies a CPU core
 * while waiting. In addition it tracks elapsed busy-spin time and can throw a
 * {@link TimeoutException} when a configured timeout is exceeded.
 */
public class BusyTimedPauser implements Pauser, TimingPauser {

    private long time = Long.MAX_VALUE;
    private long countPaused = 0;

    /**
     * Always returns {@code true}, indicating that this pauser predominantly keeps the thread busy.
     *
     * @return {@code true}, as the primary operation is a busy wait
     */
    @Override
    public boolean isBusy() {
        return true;
    }

    /**
     * Clears any timeout state so the next timed pause starts afresh.
     */
    @Override
    public void reset() {
        time = Long.MAX_VALUE;
    }

    /**
     * Busy-spins once and increments the pause count.
     * No yielding or sleeping occurs.
     */
    @Override
    public void pause() {
        countPaused++;
        Jvm.nanoPause();
    }

    /**
     * Busy-spins until the accumulated pause time exceeds the supplied timeout.
     * The timer starts with the first call after {@link #reset()}.
     *
     * @param timeout  maximum time to spin before throwing an exception
     * @param timeUnit unit for {@code timeout}
     * @throws TimeoutException if the time since the first call exceeds the timeout
     */
    @Override
    public void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        if (time == Long.MAX_VALUE)
            time = System.nanoTime();
        if (System.nanoTime() - time > timeUnit.toNanos(timeout))
            throw new TimeoutException("Pause timed out after " + timeout + " " + timeUnit);
        pause();
    }

    /**
     * Does nothing since this implementation has no state to unpause from. The method exists to fulfill the interface contract.
     */
    @Override
    public void unpause() {
        // nothing to unpause.
    }

    /**
     * Always returns {@code 0} as this pauser does not actually track total pause time.
     *
     * @return {@code 0}, indicating no measurable pause duration
     */
    @Override
    public long timePaused() {
        return 0;
    }

    /**
     * Returns the count of how many times the {@code pause()} method has been called.
     *
     * @return the number of pauses that have been initiated
     */
    @Override
    public long countPaused() {
        return countPaused;
    }

    /**
     * Provides a string representation for this pauser, identifying it as "PauserMode.timedBusy".
     *
     * @return a string indicating the type of pauser
     */
    @Override
    public String toString() {
        return "PauserMode.timedBusy";
    }
}

