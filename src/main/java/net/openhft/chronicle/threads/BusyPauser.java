/*
 * Copyright 2016-2020 chronicle.software
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
     * @throws TimeoutException never thrown
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
