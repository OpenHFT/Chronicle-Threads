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

import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Created by rob on 30/11/2015.
 */
public class MilliPauser implements Pauser {
    private final AtomicBoolean pausing = new AtomicBoolean();
    private long pauseTimeMS;
    private long timePaused = 0;
    private long countPaused = 0;

    /**
     * first it will busy wait, then it will yield, then sleep for a small amount of time, then
     * increases to a large amount of time.
     *
     * @param pauseTimeMS the pause time for each loop.
     */
    public MilliPauser(long pauseTimeMS) {
        this.pauseTimeMS = pauseTimeMS;
    }

    public MilliPauser pauseTimeMS(long pauseTimeMS) {
        this.pauseTimeMS = pauseTimeMS;
        return this;
    }

    public MilliPauser minPauseTimeMS(long pauseTimeMS) {
        this.pauseTimeMS = Math.min(this.pauseTimeMS, pauseTimeMS);
        if (this.pauseTimeMS < 1)
            this.pauseTimeMS = 1;
        return this;
    }

    public long pauseTimeMS() {
        return pauseTimeMS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void pause() {
        doPauseMS(pauseTimeMS);
    }

    void doPauseMS(long delayMS) {
        long start = System.nanoTime();
        pausing.set(true);
        Jvm.pause(delayMS);
        pausing.set(false);
        long time = System.nanoTime() - start;
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
