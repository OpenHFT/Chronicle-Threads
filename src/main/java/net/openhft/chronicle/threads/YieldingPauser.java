/*
 * Copyright 2016-2025 chronicle.software
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
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pauser that spins for a fixed number of calls and then yields.
 *
 * <p>It consumes less CPU than {@link BusyPauser} yet avoids the sleeping
 * stage used by {@link LongPauser}. Use it when short bursts of activity are
 * expected but the thread must remain responsive.</p>
 */
public class YieldingPauser implements TimingPauser {
    final int minBusy;
    int count = 0;
    private long timePaused = 0;
    private long countPaused = 0;
    private long yieldStart = 0;
    private long timeOutStart = Long.MAX_VALUE;

    /**
     * @param minBusy number of {@link #pause()} calls to spin before yielding.
     *                A value of {@code 0} yields immediately.
     */
    public YieldingPauser(int minBusy) {
        this.minBusy = minBusy;
    }

    @Override
    public void reset() {
        checkYieldTime();
        count = 0;
        timeOutStart = Long.MAX_VALUE;
    }

    /**
     * Increments an internal counter and either spins or yields.
     *
     * <p>While the count is below {@code minBusy} a safepoint is executed and
     * the method returns. Once the threshold is passed the thread yields and the
     * time spent yielding is measured.</p>
     */
    @Override
    public void pause() {
        ++count;
        if (count < minBusy) {
            ++countPaused;
            Jvm.safepoint();
            return;
        }
        yield0();
        checkYieldTime();
    }

    /**
     * Variant of {@link #pause()} that fails after the given timeout.
     *
     * <p>The first call records the start time. Once yielding begins the elapsed
     * time is checked and a {@link TimeoutException} is thrown when the limit is
     * exceeded.</p>
     *
     * @param timeout  maximum time to wait
     * @param timeUnit unit of the timeout
     * @throws TimeoutException if the elapsed time passes the timeout
     */
    @Override
    public void pause(long timeout, @NotNull TimeUnit timeUnit) throws TimeoutException {
        if (timeOutStart == Long.MAX_VALUE)
            timeOutStart = System.nanoTime();

        ++count;
        if (count < minBusy)
            return;
        yield0();

        if (System.nanoTime() - timeOutStart > timeUnit.toNanos(timeout))
            throw new TimeoutException();
        checkYieldTime();
    }

    /**
     * Records and accumulates the duration of yielding if any, and resets the start time of yielding.
     */
    void checkYieldTime() {
        if (yieldStart > 0) {
            long time = System.nanoTime() - yieldStart;
            timePaused += time;
            countPaused++;
            yieldStart = 0;
        }
    }

    /**
     * Initiates or continues a yielding phase for this pauser.
     */
    void yield0() {
        if (yieldStart == 0)
            yieldStart = System.nanoTime();
        Thread.yield();
    }

    @Override
    public void unpause() {
        // Do nothing
    }

    /**
     * Returns the total time this pauser has spent yielding, measured in milliseconds.
     *
     * @return total yielding time in milliseconds
     */
    @Override
    public long timePaused() {
        return timePaused / 1_000_000;
    }

    /**
     * Returns the number of times this pauser has been activated, including both busy-wait and yield iterations.
     *
     * @return the total number of pause activations
     */
    @Override
    public long countPaused() {
        return countPaused;
    }

    /**
     * Provides a string representation of this pauser, which varies based on the {@code minBusy} configuration.
     *
     * @return a string representation identifying the mode and settings of this pauser
     */
    @Override
    public String toString() {
        if (minBusy == 2)
            return "PauserMode.yielding";
        return "YieldingPauser{" +
                "minBusy=" + minBusy +
                '}';
    }
}
