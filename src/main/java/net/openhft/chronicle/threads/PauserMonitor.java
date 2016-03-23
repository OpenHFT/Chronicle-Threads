/*
 *
 *  *     Copyright (C) 2016  higherfrequencytrading.com
 *  *
 *  *     This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU Lesser General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU Lesser General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU Lesser General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;


/**
 * Created by peter.lawrey on 04/01/2016.
 */
public class PauserMonitor implements EventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PauserMonitor.class);

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
