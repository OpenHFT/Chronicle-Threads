/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.threads.CoreEventLoop;
import net.openhft.chronicle.threads.ThreadHolder;
/**
 * {@link ThreadHolder} implementation used to monitor a single event loop
 * thread.  It keeps track of how long the loop has been running and requests a
 * dump of the loop's state when the thread appears to have blocked for longer
 * than the configured monitoring interval.  Each subsequent dump is spaced
 * further apart to reduce log volume while the loop remains stuck.
 */

public class EventLoopThreadHolder implements ThreadHolder {
    private final CoreEventLoop eventLoop;
    private final long monitorIntervalNS;
    // additional time added to the next logging threshold
    private long intervalToAddNS;
    // nanoseconds before the next thread dump is logged
    private long printBlockTimeNS;

    public EventLoopThreadHolder(long monitorIntervalNS, CoreEventLoop eventLoop) {
        this.monitorIntervalNS = intervalToAddNS = printBlockTimeNS = monitorIntervalNS;
        this.eventLoop = eventLoop;
    }

    @Override
    public boolean isAlive() {
        return eventLoop.isAlive();
    }

    @Override
    public void reportFinished() {
        Jvm.warn().on(getClass(), "Monitoring a task which has finished " + eventLoop);
    }

    @Override
    public long startedNS() {
        return eventLoop.loopStartNS();
    }

    @Override
    public void resetTimers() {
        intervalToAddNS =
                printBlockTimeNS = monitorIntervalNS;
    }

    @Override
    public boolean shouldLog(long nowNS) {
        long blockingTimeNS = nowNS - startedNS();
        return blockingTimeNS >= printBlockTimeNS;
    }

    @Override
    public void dumpThread(long startedNS, long nowNS) {
        long blockingTimeNS = nowNS - startedNS;
        double blockingTimeMS = Math.floor(blockingTimeNS / 100_000d) / 10d;
        if (blockingTimeMS <= 0.0)
            return;
        eventLoop.dumpRunningState(eventLoop.name() + " thread has blocked for "
                        + blockingTimeMS + " ms.",
                // check we are still in the loop.
                () -> eventLoop.loopStartNS() == startedNS);

        printBlockTimeNS += intervalToAddNS;
        intervalToAddNS = (long) Math.min(1.41d * intervalToAddNS, 20d * monitorIntervalNS);
    }

    @Override
    public long timingToleranceNS() {
        return monitorIntervalNS + timingErrorNS();
    }

    protected long timingErrorNS() {
        return TIMING_ERROR;
    }

    @Override
    public String getName() {
        return eventLoop.name();
    }

    @Override
    public void monitorThreadDelayed(long actionCallDelayNS) {
        // report it??
    }
}
