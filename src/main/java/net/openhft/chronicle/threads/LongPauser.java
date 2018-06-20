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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.ThreadHints;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/*
 * Created by rob on 30/11/2015.
 */
public class LongPauser implements Pauser, TimingPauser {
    private static final String SHOW_PAUSES = System.getProperty("pauses.show");
    private final long minPauseTimeNS;
    private final long maxPauseTimeNS;
    private final AtomicBoolean pausing = new AtomicBoolean();
    private final int minBusy, minCount;
    private int count = 0;
    private long pauseTimeNS;
    private long timePaused = 0;
    private long countPaused = 0;
    @Nullable
    private volatile Thread thread = null;
    private long yieldStart = 0;
    private long timeOutStart = Long.MAX_VALUE;

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
    public LongPauser(int minBusy, int minCount, long minTime, long maxTime, @NotNull TimeUnit timeUnit) {
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
        timeOutStart = Long.MAX_VALUE;
    }

    @Override
    public void pause() {
        ++count;
        if (count < minBusy) {
            Jvm.safepoint();
            ThreadHints.onSpinWait();
            return;
        }

        checkYieldTime();
        if (count <= minBusy + minCount) {
            yield();
            return;
        }
        if (SHOW_PAUSES != null) {
            showPauses();
        }

        doPause(pauseTimeNS);
        pauseTimeNS = Math.min(maxPauseTimeNS, pauseTimeNS + (pauseTimeNS >> 6) + 20_000);
    }

    private void showPauses() {
        String name = Thread.currentThread().getName();
        if (name.startsWith(SHOW_PAUSES))
            System.out.println(name + " p" + pauseTimeNS / 1000);
    }

    @Override
    public void pause(long timeout, @NotNull TimeUnit timeUnit) throws TimeoutException {
        ++count;
        if (count < minBusy)
            return;
        if (count <= minBusy + minCount) {
            yield();
            return;
        }
        if (timeOutStart == Long.MAX_VALUE)
            timeOutStart = System.nanoTime();
        else if (timeOutStart + timeUnit.toNanos(timeout) < System.nanoTime())
            throw new TimeoutException();
        checkYieldTime();
        doPause(pauseTimeNS);
        pauseTimeNS = Math.min(maxPauseTimeNS, pauseTimeNS + (pauseTimeNS >> 7) + 10_000);
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
