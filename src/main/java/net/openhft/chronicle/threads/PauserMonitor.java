/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
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
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;

public class PauserMonitor implements EventHandler {

    public static final boolean PERF_ENABLED = Jvm.isDebugEnabled(PauserMonitor.class);
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
        final Pauser pauserSnapshot = this.pauser.get();
        if (pauserSnapshot == null)
            throw new InvalidEventHandlerException();
        long timePaused = pauserSnapshot.timePaused();
        long countPaused = pauserSnapshot.countPaused();

        if (nextLongTime > 0) {
            long timeDelta = now - lastTime;
            long timePausedDelta = timePaused - lastTimePaused;
            long countPausedDelta = countPaused - lastCountPaused;
            if (countPausedDelta > 0) {
                double averageTime = timePausedDelta * 1000L / countPausedDelta / 1e3;
                // sometimes slightly negative due to rounding error
                double busy = Math.abs((timeDelta - timePausedDelta) * 1000L / timeDelta / 10.0);
                if (PERF_ENABLED)
                    Jvm.perf().on(getClass(), description + ": avg pause: " + averageTime + " ms, "
                            + "count=" + countPausedDelta
                            + (lastTime > 0 ? ", busy=" + busy + "%" : ""));
            } else {
                if (PERF_ENABLED)
                    Jvm.perf().on(getClass(), description + ": count=" + countPausedDelta + ", busy=100%");
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

    @Override
    public String toString() {
        return "PauserMonitor<" + description + '>';
    }
}
