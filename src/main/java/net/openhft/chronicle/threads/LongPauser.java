package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;

import java.util.concurrent.TimeUnit;

/**
 * Created by rob on 30/11/2015.
 */
public class LongPauser implements Pauser {

    private final int timeMs;
    private int count;

    public LongPauser(long time, TimeUnit timeUnit) {
        this.timeMs = Maths.toUInt31(timeUnit.toMillis(time));
    }

    @Override
    public void reset() {
        count = 0;
    }

    @Override
    public void pause() {
        if (count++ == 0)
            return;
        if (count > timeMs)
            count = timeMs;
        Jvm.pause(count);
    }

    @Override
    public void pause(long maxPauseNS) {
        if (count++ == 0)
            return;
        if (count > timeMs)
            count = timeMs;
        Jvm.pause(Math.min(count, maxPauseNS / 1000000));
    }

    @Override
    public void unpause() {

    }
}
