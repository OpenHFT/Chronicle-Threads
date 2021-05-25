package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

public interface ThreadHolder {
    int TIMING_ERROR = Integer.getInteger("threads.timing.error", 20_000_000);

    boolean isAlive() throws InvalidEventHandlerException;

    void reportFinished();

    void resetTimers();

    long startedNS();

    boolean shouldLog(long nowNS);

    void dumpThread(long startedNS, long nowNS);

    String getName();

    void monitorThreadDelayed(long actionCallDelayNS);

    long timingTolerance();
}
