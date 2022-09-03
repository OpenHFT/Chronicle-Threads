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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public enum BusyPauser implements Pauser {
    INSTANCE;
    private long countPaused = 0;

    @Override
    public void reset() {
        // Do nothing
    }

    @Override
    public void pause() {
        Jvm.nanoPause();
        countPaused++;
    }

    @Override
    public void pause(long timeout, TimeUnit timeUnit) throws TimeoutException {
        throw new UnsupportedOperationException(this + " is not stateful, use a " + BusyTimedPauser.class.getSimpleName());
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
        return countPaused;
    }

    @Override
    public boolean isBusy() {
        return true;
    }
}
