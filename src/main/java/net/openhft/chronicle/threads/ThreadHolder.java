/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

/**
 * Supplies runtime details of a thread or event loop being monitored.  The
 * associated {@link ThreadMonitor} uses this information to detect long blocks
 * or unexpected thread termination.
 */
public interface ThreadHolder {
    int TIMING_ERROR = Jvm.getInteger("threads.timing.error", 80_000_000);

    /**
     * Indicates whether the monitored thread is still running.
     *
     * @return {@code true} if the thread has not terminated
     */
    boolean isAlive();

    /**
     * Called once the thread has ended so monitoring can be stopped or logged.
     */
    void reportFinished();

    /**
     * Clears any internal timers when a new loop iteration begins.
     */
    void resetTimers();

    /**
     * Get the {@link System#nanoTime()} at which the currently executing loop iteration started
     *
     * @return The time the current loop started, or {@link CoreEventLoop#NOT_IN_A_LOOP} if no iteration is executing
     */
    long startedNS();

    /**
     * Determines whether a block has exceeded the logging threshold.
     *
     * @param nowNS the current time in nanoseconds
     * @return {@code true} if logging should occur
     */
    boolean shouldLog(long nowNS);

    /**
     * Produces a diagnostic dump when a stall is detected.
     *
     * @param startedNS when the loop iteration began
     * @param nowNS     the time the dump is triggered
     */
    void dumpThread(long startedNS, long nowNS);

    /**
     * Descriptive name used in log output.
     */
    String getName();

    /**
     * Notifies that the monitor thread itself was delayed.
     *
     * @param actionCallDelayNS time since the last monitor call in nanoseconds
     */
    void monitorThreadDelayed(long actionCallDelayNS);

    /**
     * Maximum delay between monitor calls before a warning is triggered.
     *
     * @return tolerance in nanoseconds
     */
    long timingToleranceNS();
}
