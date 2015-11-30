package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.util.concurrent.TimeUnit;

/**
 * Created by rob on 30/11/2015.
 */
public class LongPauser implements Pauser {

    private long timeNs;

    public LongPauser(long time, TimeUnit timeUnit) {
        this.timeNs = timeUnit.toNanos(time);
    }

    @Override
    public void reset() {

    }

    @Override
    public void pause() {
        Jvm.pause(timeNs);

    }

    @Override
    public void pause(long maxPauseNS) {
        Jvm.pause(Math.min(timeNs, maxPauseNS));
    }

    @Override
    public void unpause() {

    }
}
