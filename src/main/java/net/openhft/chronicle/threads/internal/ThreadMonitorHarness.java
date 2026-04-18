/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.ThreadHolder;
import net.openhft.chronicle.threads.ThreadMonitor;

import java.util.function.LongSupplier;

import static net.openhft.chronicle.threads.CoreEventLoop.NOT_IN_A_LOOP;

/**
 * Monitoring harness that drives a {@link ThreadHolder} via the
 * {@link ThreadMonitor} interface.  The harness delegates all
 * monitoring actions to the wrapped holder and records the last time
 * an action was run.
 */
public class ThreadMonitorHarness implements ThreadMonitor {
    private final ThreadHolder thread;
    private final LongSupplier timeSupplier;
    private long lastActionCall = Long.MAX_VALUE;
    private long lastStartedNS = NOT_IN_A_LOOP;

    /**
     * Creates a harness that reports on the supplied holder using the given
     * time supplier.
     *
     * @param thread       holder describing the monitored thread
     * @param timeSupplier provider of the current time in nanoseconds
     */
    public ThreadMonitorHarness(ThreadHolder thread, LongSupplier timeSupplier) {
        this.thread = thread;
        this.timeSupplier = timeSupplier;
    }

    /**
     * Creates a harness using {@link System#nanoTime()} as the time provider.
     *
     * @param thread holder describing the monitored thread
     */
    public ThreadMonitorHarness(ThreadHolder thread) {
        this(thread, System::nanoTime);
    }

    /**
     * Called periodically to check the state of the wrapped thread.
     * Throws {@link InvalidEventHandlerException} if the thread has
     * finished.  If a delay greater than the tolerance is observed the
     * holder is notified and {@code true} is returned.
     *
     * @return {@code true} when the holder reports a delay
     * @throws InvalidEventHandlerException if the thread is no longer alive
     */
    @Override
    public boolean action() throws InvalidEventHandlerException {
        if (!thread.isAlive()) {
            thread.reportFinished();
            throw new InvalidEventHandlerException();
        }
        long startedNS = thread.startedNS();
        long nowNS = timeSupplier.getAsLong();

        // Record lastActionCall time on every call to prevent false-positive "monitorThreadDelayed" reports
        long actionCallDelay = nowNS - this.lastActionCall;
        this.lastActionCall = nowNS;

        if (startedNS == 0 || startedNS == NOT_IN_A_LOOP) {
            return false;
        }
        if (startedNS != lastStartedNS) {
            thread.resetTimers();
            lastStartedNS = startedNS;
        }
        if (actionCallDelay > thread.timingToleranceNS()) {
            if (thread.isAlive())
                thread.monitorThreadDelayed(actionCallDelay);
            return true;
        }
        if (!thread.shouldLog(nowNS))
            return false;
        thread.dumpThread(startedNS, nowNS);
        return false; // true assumes we are about to need to check again.
    }

    @Override
    public String toString() {
        return "ThreadMonitorHarness<" + thread.getName() + ">";
    }
}
