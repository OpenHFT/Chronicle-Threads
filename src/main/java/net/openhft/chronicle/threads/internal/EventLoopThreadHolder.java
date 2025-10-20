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
        double blockingTimeMS = blockingTimeNS / 100_000 / 10.0;
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
