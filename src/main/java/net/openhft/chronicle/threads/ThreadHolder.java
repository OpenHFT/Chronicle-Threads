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

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

/**
 * The {@code ThreadHolder} interface provides methods for monitoring and managing a thread's execution state
 * within an event loop or other looping construct. Implementations of this interface should allow tracking
 * of loop timing, reporting of completed cycles, and handling of thread delays for diagnostic purposes.
 */
public interface ThreadHolder {

    /**
     * The threshold for timing errors, in nanoseconds. If the timing error exceeds this threshold, it may indicate
     * significant delays or scheduling issues.
     */
    int TIMING_ERROR = Jvm.getInteger("threads.timing.error", 80_000_000);

    /**
     * Checks if the thread or event loop associated with this {@code ThreadHolder} is still active and running.
     *
     * @return {@code true} if the thread or event loop is alive, {@code false} otherwise
     * @throws InvalidEventHandlerException if the handler associated with the thread is invalid
     */
    boolean isAlive() throws InvalidEventHandlerException;

    /**
     * Reports that the thread has finished its current task or loop iteration.
     */
    void reportFinished();

    /**
     * Resets any internal timers or counters used for tracking the thread's timing within an event loop.
     */
    void resetTimers();

    /**
     * Gets the {@link System#nanoTime()} value at which the currently executing loop iteration started.
     *
     * @return the start time of the current loop iteration, or {@link CoreEventLoop#NOT_IN_A_LOOP}
     *         if no iteration is currently executing
     */
    long startedNS();

    /**
     * Determines whether a log message should be generated based on the current time.
     *
     * @param nowNS the current time in nanoseconds
     * @return {@code true} if a log message should be generated, {@code false} otherwise
     */
    boolean shouldLog(long nowNS);

    /**
     * Dumps information about the current thread state, including timing and diagnostic data, for analysis.
     *
     * @param startedNS the time at which the current thread started
     * @param nowNS     the current time in nanoseconds
     */
    void dumpThread(long startedNS, long nowNS);

    /**
     * Gets the name of the thread associated with this {@code ThreadHolder}.
     *
     * @return the name of the thread
     */
    String getName();

    /**
     * Monitors for delays in the thread by comparing the specified delay against the expected timing.
     *
     * @param actionCallDelayNS the delay in nanoseconds since the last action call
     */
    void monitorThreadDelayed(long actionCallDelayNS);

    /**
     * Gets the timing tolerance for this thread in nanoseconds, which can be used to determine if the thread's
     * execution is within acceptable time limits.
     *
     * @return the timing tolerance in nanoseconds
     */
    long timingToleranceNS();
}
