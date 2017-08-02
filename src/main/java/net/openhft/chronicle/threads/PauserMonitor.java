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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;

/*
 * Created by peter.lawrey on 04/01/2016.
 */
public class PauserMonitor implements EventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PauserMonitor.class);

    @NotNull
    private final WeakReference<Pauser> pauser;
    private final String description;
    private final int mills;
    private long nextLongTime = 0;
    private long lastTime = 0;
    private long lastTimePaused = 0;
    private long lastCountPaused = 0;

    public PauserMonitor(Pauser pauser, String description, int seconds) {
        this.pauser = new WeakReference<>(pauser);
        this.description = description;
        this.mills = seconds * 1000;
    }

    @Override
    public boolean action() throws InvalidEventHandlerException {
        long now = System.currentTimeMillis();
        if (nextLongTime > now) {
            return false;
        }
        Pauser pauser = this.pauser.get();
        if (pauser == null)
            throw new InvalidEventHandlerException();
        long timePaused = pauser.timePaused();
        long countPaused = pauser.countPaused();

        if (nextLongTime > 0) {
            long timeDelta = now - lastTime;
            long timePausedDelta = timePaused - lastTimePaused;
            long countPausedDelta = countPaused - lastCountPaused;
            if (countPausedDelta > 0) {
                double averageTime = timePausedDelta * 1000 / countPausedDelta / 1e3;
                // sometimes slightly negative due to rounding error
                double busy = Math.abs((timeDelta - timePausedDelta) * 1000 / timeDelta / 10.0);
                LOG.info(description + ": avg pause: " + averageTime + " ms, "
                        + "count=" + countPausedDelta
                        + (lastTime > 0 ? ", busy=" + busy + "%" : ""));
            } else {
                LOG.info(description + ": count=" + countPausedDelta + ", busy=100%");
            }
        }
        lastTimePaused = timePaused;
        lastCountPaused = countPaused;
        nextLongTime = now + mills;
        lastTime = now;
        return true;
    }

    @NotNull
    @Override
    public HandlerPriority priority() {
        return HandlerPriority.MONITOR;
    }
}
