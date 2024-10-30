/*
 * Copyright 2016-2022 chronicle.software
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

package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.threads.CoreEventLoop;
import net.openhft.chronicle.threads.ThreadHolder;

/**
 * Manages and monitors the state of an {@link CoreEventLoop} thread, providing functionality
 * to check its status, log blocking durations, and handle timing thresholds.
 * <p>
 * This class is useful for detecting and reporting delays in an event loop's operation
 * by keeping track of a monitor interval and dynamically adjusting the block time thresholds.
 */
public class EventLoopThreadHolder implements ThreadHolder {
    private final CoreEventLoop eventLoop;
    private final long monitorIntervalNS;
    private long intervalToAddNS;
    private long printBlockTimeNS;

    /**
     * Initializes the {@code EventLoopThreadHolder} with a specified monitoring interval and
     * the associated {@link CoreEventLoop}.
     *
     * @param monitorIntervalNS the initial interval in nanoseconds between monitoring checks
     * @param eventLoop         the event loop to be monitored
     */
    public EventLoopThreadHolder(long monitorIntervalNS, CoreEventLoop eventLoop) {
        this.monitorIntervalNS = intervalToAddNS = printBlockTimeNS = monitorIntervalNS;
        this.eventLoop = eventLoop;
    }

    /**
     * Checks if the associated event loop thread is alive.
     *
     * @return {@code true} if the event loop is active, {@code false} otherwise
     */
    @Override
    public boolean isAlive() {
        return eventLoop.isAlive();
    }

    /**
     * Logs a warning indicating that monitoring was attempted on a finished event loop.
     */
    @Override
    public void reportFinished() {
        Jvm.warn().on(getClass(), "Monitoring a task which has finished " + eventLoop);
    }

    /**
     * Returns the start time of the current loop iteration in nanoseconds.
     *
     * @return the start time of the loop in nanoseconds, or a constant if not in a loop
     */
    @Override
    public long startedNS() {
        return eventLoop.loopStartNS();
    }

    /**
     * Resets internal timers for monitoring intervals.
     * Useful when starting a new monitoring cycle.
     */
    @Override
    public void resetTimers() {
        intervalToAddNS =
                printBlockTimeNS = monitorIntervalNS;
    }

    /**
     * Determines if a blocking event should be logged based on the elapsed time since
     * the last loop start.
     *
     * @param nowNS the current time in nanoseconds
     * @return {@code true} if the elapsed time exceeds the threshold for logging, {@code false} otherwise
     */
    @Override
    public boolean shouldLog(long nowNS) {
        long blockingTimeNS = nowNS - startedNS();
        return blockingTimeNS >= printBlockTimeNS;
    }

    /**
     * Logs the state of the event loop if it has been blocked for a significant duration.
     *
     * @param startedNS the start time of the blocking in nanoseconds
     * @param nowNS     the current time in nanoseconds
     */
    @Override
    public void dumpThread(long startedNS, long nowNS) {
        long blockingTimeNS = nowNS - startedNS;
        double blockingTimeMS = blockingTimeNS / 100_000 / 10.0;
        if (blockingTimeMS <= 0.0)
            return;
        eventLoop.dumpRunningState(eventLoop.name() + " thread has blocked for "
                        + blockingTimeMS + " ms.",
                // check we are still in the loop.
                () -> eventLoop.loopStartNS() == startedNS);

        // Incrementally increase block time interval to avoid excessive logging
        printBlockTimeNS += intervalToAddNS;
        intervalToAddNS = (long) Math.min(1.41d * intervalToAddNS, 20d * monitorIntervalNS);
    }

    /**
     * Provides the maximum timing tolerance in nanoseconds for monitoring.
     *
     * @return the sum of the monitor interval and timing error in nanoseconds
     */
    @Override
    public long timingToleranceNS() {
        return monitorIntervalNS + timingErrorNS();
    }

    /**
     * Returns the expected timing error in nanoseconds.
     *
     * @return the timing error in nanoseconds
     */
    protected long timingErrorNS() {
        return TIMING_ERROR;
    }

    /**
     * Retrieves the name of the event loop being monitored.
     *
     * @return the name of the event loop
     */
    @Override
    public String getName() {
        return eventLoop.name();
    }

    /**
     * Optional callback for handling delays in the thread’s monitoring.
     *
     * @param actionCallDelayNS the delay in nanoseconds since the last action call
     */
    @Override
    public void monitorThreadDelayed(long actionCallDelayNS) {
        // report it??
    }
}
