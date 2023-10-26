/*
 * Copyright 2016-2020 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class LongPauser implements Pauser, TimingPauser {
    private static final String SHOW_PAUSES = Jvm.getProperty("pauses.show");
    private final long minPauseTimeNS;
    private final long maxPauseTimeNS;
    private final AtomicBoolean pausing = new AtomicBoolean();
    private final long minBusyNS;
    private final long minYieldNS;
    private long firstPauseNS = Long.MAX_VALUE;
    private long pauseTimeNS;
    private long timePaused = 0;
    private long countPaused = 0;
    @Nullable
    private volatile Thread thread = null;
    private long yieldStart = 0;
    private long pauseUntilNS = 0;

    /**
     * first it will busy wait, then it will yield, then sleep for a small amount of time, then
     * increases to a large amount of time.
     *
     * @param minBusy  the length in timeUnit to go around doing nothing, after this is
     *                 reached it will then start to yield
     * @param minYield the length in timeUnit it will yield, before it starts to sleep
     * @param minTime  the amount of time to sleep ( initially )
     * @param maxTime  the amount of time subsequently to sleep
     * @param timeUnit the unit of the {@code minTime}  and {@code maxTime}
     */
    public LongPauser(int minBusy, int minYield, long minTime, long maxTime, @NotNull TimeUnit timeUnit) {
        this.minBusyNS = timeUnit.toNanos(minBusy);
        this.minYieldNS = timeUnit.toNanos(minYield);
        this.minPauseTimeNS = timeUnit.toNanos(minTime);
        this.maxPauseTimeNS = timeUnit.toNanos(maxTime);
        pauseTimeNS = minPauseTimeNS;
    }

    @Override
    public void reset() {
        if (yieldStart > 0) {
            checkYieldTime();
            countPaused++;
        }
        pauseTimeNS = minPauseTimeNS;
        pauseUntilNS = 0;
        if (SHOW_PAUSES != null && firstPauseNS < Long.MAX_VALUE)
            showPauses();
        firstPauseNS = Long.MAX_VALUE;
    }

    @Override
    public void pause() {
        try {
            pause(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
        }
    }

    @Override
    public void asyncPause() {
        pauseUntilNS = System.nanoTime() + pauseTimeNS;
        increasePauseTimeNS();
    }

    @Override
    public boolean asyncPausing() {
        return pauseUntilNS > System.nanoTime();
    }

    private void showPauses() {
        String name = Thread.currentThread().getName();
        if (name.startsWith(SHOW_PAUSES))
            Jvm.perf().on(getClass(), " paused for " + (System.nanoTime() - firstPauseNS) / 1e6 + " ms.");
    }

    /**
     * Implementation of the pause method for the LongPauser. This method introduces pauses of
     * increasing duration up to a specified timeout. If the timeout is exceeded, a TimeoutException is thrown.
     *
     * @param timeout  The maximum time duration to wait before throwing a TimeoutException.
     * @param timeUnit The unit of the timeout parameter.
     * @throws TimeoutException If the pause exceeds the specified timeout.
     */
    @Override
    public void pause(long timeout, @NotNull TimeUnit timeUnit) throws TimeoutException {
        // Increment the pause count
        countPaused++;

        // Get the current time in nanoseconds
        final long now = System.nanoTime();

        // Check if this is the first pause; if so, record the time of the first pause
        if (firstPauseNS == Long.MAX_VALUE)
            firstPauseNS = now;

        // If the current time is within the minimum busy period, do a nanosecond pause and return
        if (now < firstPauseNS + minBusyNS) {
            Jvm.nanoPause();
            return;
        }

        // If the current time is within the minimum yield period, yield the thread and return
        if (now < firstPauseNS + minYieldNS) {
            this.yield();
            return;
        }

        // If a finite timeout is given, check if it's exceeded and throw a TimeoutException if it is
        if (timeout < Long.MAX_VALUE) {
            if (firstPauseNS + timeUnit.toNanos(timeout) - now < 0)
                throw new TimeoutException();
        }

        // Check the yield time to determine whether to continue yielding or to move to the next pause strategy
        checkYieldTime();

        // Pause for the current pause duration
        doPause(pauseTimeNS);

        // Increase the pause duration for the next pause
        increasePauseTimeNS();
    }

    private void increasePauseTimeNS() {
        pauseTimeNS = Math.min(maxPauseTimeNS, pauseTimeNS + (pauseTimeNS >> 6) + 10_000);
    }

    private void checkYieldTime() {
        if (yieldStart > 0) {
            long time = System.nanoTime() - yieldStart;
            timePaused += time;
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
        if (!thread.isInterrupted())
            LockSupport.parkNanos(delayNs);
        pausing.set(false);
        long time = System.nanoTime() - start;
        timePaused += time;
    }

    @Override
    public void unpause() {
        final Thread threadSnapshot = this.thread;
        if (threadSnapshot != null && pausing.get())
            LockSupport.unpark(threadSnapshot);
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
