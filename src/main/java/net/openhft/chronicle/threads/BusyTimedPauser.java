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
 * A pauser implementation similar to {@link BusyPauser} that primarily keeps the thread busy
 * while supporting timeout-based pausing through the {@link TimingPauser} interface.
 */
public class BusyTimedPauser implements Pauser, TimingPauser {

    private long time = Long.MAX_VALUE;  // Tracks the starting time for timing pauses
    private long countPaused = 0;        // Counts the number of times the pause method is called

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
     * Resets the internal timer, allowing a fresh timing period to begin upon the next pause.
     */
    @Override
    public void reset() {
        time = Long.MAX_VALUE;
    }

    /**
     * Increments the pause counter and induces a busy wait. This method keeps the thread active
     * rather than idling, with a brief pause implemented using {@link Jvm#nanoPause()}.
     */
    @Override
    public void pause() {
        countPaused++;
        // busy wait.
        Jvm.nanoPause();
    }

    /**
     * Attempts to pause the thread with a specified timeout. If the pause exceeds the specified duration,
     * a {@link TimeoutException} is thrown, indicating the timeout has elapsed without resumption of operations.
     *
     * @param timeout  the maximum time to wait before throwing an exception
     * @param timeUnit the unit of time for the timeout parameter
     * @throws TimeoutException if the wait exceeds the specified timeout duration
     */
    @Override
    public void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        if (time == Long.MAX_VALUE)  // Initialize the start time only once per timeout period
            time = System.nanoTime();
        if (System.nanoTime() - time > timeUnit.toNanos(timeout))
            throw new TimeoutException("Pause timed out after " + timeout + " " + timeUnit);
        pause();  // Perform the actual pause
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
