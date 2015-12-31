package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.util.concurrent.TimeUnit;

/**
 * Created by rob on 30/11/2015.
 */
public class LongPauser implements Pauser {

    private final long timeMs;

    public LongPauser(long time, TimeUnit timeUnit) {
        this.timeMs = timeUnit.toMillis(time);
    }

    @Override
    public void reset() {

    }

    @Override
    public void pause() {
        Jvm.pause(timeMs);
    }

    @Override
    public void pause(long maxPauseNS) {
        Jvm.pause(Math.min(timeMs, maxPauseNS / 1000000));
    }

    @Override
    public void unpause() {

    }
}
