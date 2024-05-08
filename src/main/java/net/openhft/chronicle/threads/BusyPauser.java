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
 * Implementation of {@link Pauser} that employs a busy-wait strategy to keep the CPU actively engaged.
 * This pauser continuously executes a very short pause (nano-pause) to keep the thread active.
 *
 * <p>Because it never actually suspends the thread, most operations related to state management (like pausing, unpausing, and timeout handling) are unsupported or no-op.</p>
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
     * Keeps the thread actively busy by executing a very short pause at the CPU level.
     * This method is primarily used to prevent the thread from yielding execution entirely.
     */
    @Override
    public void pause() {
        Jvm.nanoPause();
    }

    /**
     * Throws {@link UnsupportedOperationException} as {@code BusyPauser} does not support pausing with a timeout.
     *
     * @param timeout  the timeout duration
     * @param timeUnit the unit of time for the timeout duration
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
