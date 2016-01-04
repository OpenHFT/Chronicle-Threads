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
    private long timePaused = 0;
    private long countPaused = 0;

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
        doPause(count);
    }

    @Override
    public void pause(long maxPauseNS) {
        if (count++ == 0)
            return;
        if (count > timeMs)
            count = timeMs;
        doPause(Math.min(count, maxPauseNS / 1000000));
    }

    void doPause(long delay) {
        long start = System.currentTimeMillis();
        Jvm.pause(delay);
        long time = System.currentTimeMillis() - start;
        timePaused += time;
        countPaused++;
    }

    @Override
    public void unpause() {

    }

    @Override
    public long timePaused() {
        return timePaused;
    }

    @Override
    public long countPaused() {
        return countPaused;
    }
}
