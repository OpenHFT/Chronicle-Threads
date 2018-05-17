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
import net.openhft.chronicle.core.annotation.ForceInline;
import net.openhft.chronicle.core.util.Time;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/*
 * Created by peter.lawrey on 11/12/14.
 * @deprecated use LongPauser instead.
 */
@Deprecated
public class LightPauser implements Pauser {
    public static final long NO_BUSY_PERIOD = -1;
    private final AtomicBoolean pausing = new AtomicBoolean();
    private final long busyPeriodNS;
    private final long parkPeriodNS;
    private int count;
    private long timePaused = 0;
    private long countPaused = 0;
    private long pauseStart = 0;
    private volatile Thread thread;

    public LightPauser(long busyPeriodNS, long parkPeriodNS) {
        this.busyPeriodNS = busyPeriodNS;
        this.parkPeriodNS = parkPeriodNS;
    }

    @Override
    @ForceInline
    public void reset() {
        pauseStart = count = 0;
    }

    @Override
    public void pause() {
        long maxPauseNS = parkPeriodNS;
        if (busyPeriodNS > 0) {
            if (count++ < 1000) {
                Jvm.safepoint();
                return;
            }
            if (pauseStart == 0) {
                pauseStart = System.nanoTime();
                return;
            }
            if (System.nanoTime() < pauseStart + busyPeriodNS)
                return;
        }
        if (maxPauseNS < 10000)
            return;
        thread = Thread.currentThread();

        pausing.set(true);
        long start = System.currentTimeMillis();
        doPause(maxPauseNS);
        long time = System.currentTimeMillis() - start;
        timePaused += time;
        countPaused++;
        pausing.set(false);
    }

    @Override
    public void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        throw new UnsupportedOperationException();
    }

    protected void doPause(long maxPauseNS) {
        Time.parkNanos(Math.max(maxPauseNS, parkPeriodNS));
    }

    @Override
    @ForceInline
    public void unpause() {
        if (pausing.get())
            LockSupport.unpark(thread);
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
