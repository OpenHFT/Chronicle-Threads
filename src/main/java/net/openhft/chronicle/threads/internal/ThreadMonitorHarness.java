package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.ThreadHolder;
import net.openhft.chronicle.threads.ThreadMonitor;

public class ThreadMonitorHarness implements ThreadMonitor {
    private final ThreadHolder thread;
    private long lastActionCall = Long.MAX_VALUE;

    public ThreadMonitorHarness(ThreadHolder thread) {
        this.thread = thread;
    }

    @Override
    public boolean action() throws InvalidEventHandlerException {
        if (!thread.isAlive()) {
            thread.reportFinished();
            throw new InvalidEventHandlerException();
        }
        long startedNS = thread.startedNS();
        long nowNS = System.nanoTime();
        if (startedNS == 0 || startedNS == Long.MAX_VALUE) {
            thread.resetTimers();
            return false;
        }
        long actionCallDelay = nowNS - this.lastActionCall;
        this.lastActionCall = nowNS;
        if (actionCallDelay > thread.timingTolerance()) {
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
