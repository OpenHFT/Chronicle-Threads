/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

/*
 * Created by Peter Lawrey on 03/03/2016.
 */
public abstract class TimedEventHandler implements EventHandler {
    private long nextRunNS = 0;

    @Override
    public boolean action() throws InvalidEventHandlerException, InterruptedException {
        long now = System.nanoTime();
        if (nextRunNS <= now) {
            long delayUS = timedAction();
            if (delayUS < 0)
                return true;
            nextRunNS = now + delayUS * 1000;
        }
        return false;
    }

    /**
     * Perform an action
     *
     * @return the delay in micro-seconds.
     */
    protected abstract long timedAction() throws InvalidEventHandlerException, InterruptedException;

    @NotNull
    @Override
    public HandlerPriority priority() {
        return HandlerPriority.TIMER;
    }
}
