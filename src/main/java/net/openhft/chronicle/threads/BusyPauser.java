/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Busy-spin implementation of {@link Pauser}.
 * <p>
 * The pauser repeatedly invokes {@link Jvm#nanoPause()} and never yields or
 * sleeps. A thread using this pauser therefore consumes an entire CPU core
 * while waiting. No state is kept, so most lifecycle methods are no-ops.
 */
public enum BusyPauser implements Pauser {
    /**
     * Singleton instance used by {@link Pauser#busy()}.
     */
    INSTANCE;

    /**
     * Does nothing as {@code BusyPauser} does not maintain state that requires resetting.
     */
    @Override
    public void reset() {
        // Do nothing
    }

    /**
     * Performs a single busy-spin step by calling {@link Jvm#nanoPause()}.
     * The call neither yields nor sleeps and therefore burns CPU cycles.
     */
    @Override
    public void pause() {
        Jvm.nanoPause();
    }

    /**
     * Unsupported operation as this pauser is stateless.
     * Use {@link BusyTimedPauser} when a timeout is required.
     *
     * @param timeout  timeout duration (ignored)
     * @param timeUnit unit of the timeout (ignored)
     * @throws TimeoutException never thrown by this implementation
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        throw new UnsupportedOperationException(this + " is not stateful, use a " + BusyTimedPauser.class.getSimpleName());
    }

    /**
     * Does nothing as {@code BusyPauser} has no pausing state to unpause from.
     */
    @Override
    public void unpause() {
        // nothing to unpause.
    }

    /**
     * Always returns {@code 0} as {@code BusyPauser} does not track paused time.
     *
     * @return {@code 0} always
     */
    @Override
    public long timePaused() {
        return 0;
    }

    /**
     * Always returns {@code 0} as {@code BusyPauser} does not count pauses.
     *
     * @return {@code 0} always
     */
    @Override
    public long countPaused() {
        return 0;
    }

    /**
     * Always returns {@code true}, indicating that this pauser keeps the thread busy rather than truly pausing it.
     *
     * @return {@code true} always
     */
    @Override
    public boolean isBusy() {
        return true;
    }

    /**
     * Provides a string representation of this pauser, identifying it as "PauserMode.busy".
     *
     * @return the string "PauserMode.busy"
     */
    @Override
    public String toString() {
        return "PauserMode.busy";
    }
}
