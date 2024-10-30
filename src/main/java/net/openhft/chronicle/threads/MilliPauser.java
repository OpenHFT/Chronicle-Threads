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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * A {@link Pauser} implementation that provides precise control over thread pausing
 * based on a specified duration in milliseconds. The {@code MilliPauser} supports both
 * synchronous and asynchronous pausing, allowing flexible thread management strategies.
 */
public class MilliPauser implements Pauser {
    private final AtomicBoolean pausing = new AtomicBoolean();
    private long pauseTimeMS;  // Configured pause duration in milliseconds
    private long timePaused = 0;  // Total time spent in paused state
    private long countPaused = 0;  // Number of pause operations initiated
    private long pauseUntilMS = 0;  // Timestamp indicating end of asynchronous pause
    @Nullable
    private transient volatile Thread thread = null;  // Thread currently in paused state

    /**
     * Constructs a new {@code MilliPauser} with a specified pause time in milliseconds.
     *
     * @param pauseTimeMS the pause duration for each pause operation, in milliseconds
     */
    public MilliPauser(long pauseTimeMS) {
        this.pauseTimeMS = pauseTimeMS;
    }

    /**
     * Sets the pause time to a specified duration in milliseconds.
     *
     * @param pauseTimeMS the new pause time in milliseconds
     * @return this {@code MilliPauser} instance for chaining
     */
    public MilliPauser pauseTimeMS(long pauseTimeMS) {
        this.pauseTimeMS = pauseTimeMS;
        return this;
    }

    /**
     * Sets the pause time to the minimum of the current or specified duration in milliseconds.
     * Ensures that the pause time does not drop below 1 millisecond.
     *
     * @param pauseTimeMS the proposed minimum pause time in milliseconds
     * @return this {@code MilliPauser} instance for chaining
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

    /**
     * Resets the pauser by clearing any pending asynchronous pause.
     */
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
     * Initiates an asynchronous pause for the configured duration, marking the pauser as
     * being in a paused state without blocking the calling thread.
     */
    @Override
    public void asyncPause() {
        pauseUntilMS = System.currentTimeMillis() + pauseTimeMS;
    }

    /**
     * Checks if the pauser is currently in an asynchronous pausing state.
     *
     * @return {@code true} if the pause is still in effect, {@code false} otherwise
     */
    @Override
    public boolean asyncPausing() {
        return pauseUntilMS > System.currentTimeMillis();
    }

    /**
     * Pauses the current thread for a specified duration.
     *
     * @param timeout  the maximum time to pause in the specified {@code timeUnit}
     * @param timeUnit the unit of time for {@code timeout}
     * @throws TimeoutException if the pause operation does not complete within the specified timeout
     */
    @Override
    public void pause(long timeout, @NotNull TimeUnit timeUnit) throws TimeoutException {
        doPauseMS(timeUnit.toMillis(timeout));
    }

    /**
     * Helper method to perform the actual pause operation with millisecond precision.
     *
     * @param delayMS the delay in milliseconds to pause the thread
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
