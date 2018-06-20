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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BusyTimedPauser implements Pauser, TimingPauser {

    private long time = Long.MAX_VALUE;

    @Override
    public void reset() {
        time = Long.MAX_VALUE;
    }

    @Override
    public void pause() {
        // busy wait.
        Jvm.optionalSafepoint();
        ThreadHints.onSpinWait();
    }

    @Override
    public void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        if (time == Long.MAX_VALUE)
            time = System.nanoTime();
        if (time + timeUnit.toNanos(timeout) < System.nanoTime())
            throw new TimeoutException();
    }

    @Override
    public void unpause() {
        // nothing to unpause.
    }

    @Override
    public long timePaused() {
        return 0;
    }

    @Override
    public long countPaused() {
        return 0;
    }
}
