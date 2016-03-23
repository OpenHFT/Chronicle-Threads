/*
 *
 *  *     Copyright (C) ${YEAR}  higherfrequencytrading.com
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

/**
 * Created by peter on 03/03/2016.
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
    protected abstract long timedAction();

    @Override
    public HandlerPriority priority() {
        return HandlerPriority.TIMER;
    }
}
