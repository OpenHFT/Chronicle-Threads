/*
 * Copyright 2015 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Created by rob on 30/11/2015.
 */
public class LongPauser implements Pauser {
    private final long minPauseTimeNS;
    private final long maxPauseTimeNS;
    private final AtomicBoolean pausing = new AtomicBoolean();
    private final int minBusy, minCount;
    private int count = 0;
    private long pauseTimeNS;
    private long timePaused = 0;
    private long countPaused = 0;
    private volatile Thread thread = null;
    private long yieldStart = 0;

    /**
     * first it will busy wait, then it will yield, then sleep for a small amount of time, then
     * increases to a large amount of time.
     *
     * @param minBusy  the min number of times it will go around doing nothing, after this is
     *                 reached it will then start to yield
     * @param minCount the number of times it will yield, before it starts to sleep
     * @param minTime  the amount of time to sleep ( initially )
     * @param maxTime  the amount of time subsequently to sleep
     * @param timeUnit the unit of the {@code minTime}  and {@code maxTime}
     */
    public LongPauser(int minBusy, int minCount, long minTime, long maxTime, TimeUnit timeUnit) {
        this.minBusy = minBusy;
        this.minCount = minCount;
        this.minPauseTimeNS = timeUnit.toNanos(minTime);
        this.maxPauseTimeNS = timeUnit.toNanos(maxTime);
        pauseTimeNS = minPauseTimeNS;
    }

    @Override
    public void reset() {
        checkYieldTime();
        pauseTimeNS = minPauseTimeNS;
        count = 0;
    }

    @Override
    public void pause() {
        ++count;
        if (count < minBusy)
            return;
        if (count <= minBusy + minCount) {
            yield();
            return;
        }
        checkYieldTime();
        doPause(pauseTimeNS);
        pauseTimeNS = Math.min(maxPauseTimeNS, pauseTimeNS + pauseTimeNS / 64);
    }

    private void checkYieldTime() {
        if (yieldStart > 0) {
            long time = System.nanoTime() - yieldStart;
            timePaused += time;
            countPaused++;
            yieldStart = 0;
        }
    }

    private void yield() {
        if (yieldStart == 0)
            yieldStart = System.nanoTime();
        Thread.yield();
    }

    void doPause(long delayNs) {
        long start = System.nanoTime();
        thread = Thread.currentThread();
        pausing.set(true);
        LockSupport.parkNanos(delayNs);
        pausing.set(false);
        long time = System.nanoTime() - start;
        timePaused += time;
        countPaused++;
    }

    @Override
    public void unpause() {
        Thread thread = this.thread;
        if (thread != null && pausing.get())
            LockSupport.unpark(thread);
    }

    @Override
    public long timePaused() {
        return timePaused / 1_000_000;
    }

    @Override
    public long countPaused() {
        return countPaused;
    }
}
