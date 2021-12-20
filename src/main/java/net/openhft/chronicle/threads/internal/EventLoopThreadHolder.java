package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.threads.CoreEventLoop;
import net.openhft.chronicle.threads.ThreadHolder;

public class EventLoopThreadHolder implements ThreadHolder {
    private final CoreEventLoop eventLoop;
    private final long monitorIntervalNS;
    private long intervalToAddNS, printBlockTimeNS;

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

        eventLoop.dumpRunningState(eventLoop.name() + " thread has blocked for "
                        + blockingTimeNS / 100_000 / 10.0 + " ms.",
                // check we are still in the loop.
                () -> eventLoop.loopStartNS() == startedNS);

        printBlockTimeNS += intervalToAddNS;
        intervalToAddNS = (long) Math.min(1.41d * intervalToAddNS, 20d * monitorIntervalNS);
    }

    @Override
    public long timingTolerance() {
        return monitorIntervalNS + timingError();
    }

    protected long timingError() {
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
