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

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code TimedEventHandler} abstract class provides a base implementation for event handlers
 * that operate based on a timed interval. It schedules an action to be executed at specified intervals
 * and calculates the next execution time based on the delay returned by {@link #timedAction()}.
 *
 * <p>This handler is configured with a {@link HandlerPriority} of {@code TIMER}.</p>
 */
public abstract class TimedEventHandler implements EventHandler {
    private long nextRunNS = 0;

    /**
     * Executes the scheduled action if the specified time interval has elapsed.
     * The {@link #timedAction()} method is called to perform the handler's main operation
     * and determine the next delay interval. If the delay is negative, the handler is re-scheduled immediately.
     *
     * @return {@code true} if the handler should be removed; {@code false} otherwise
     * @throws InvalidEventHandlerException if an error occurs during execution
     */
    @Override
    public boolean action() throws InvalidEventHandlerException {
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
     * The main operation to be executed by the handler. Implementations should define
     * the specific action to be performed and return the desired delay interval before the next execution.
     *
     * @return the delay in microseconds before the next execution
     * @throws InvalidEventHandlerException if an error occurs during execution
     */
    protected abstract long timedAction() throws InvalidEventHandlerException;

    /**
     * Specifies the priority of this handler as {@link HandlerPriority#TIMER}.
     *
     * @return the {@code TIMER} priority level
     */
    @NotNull
    @Override
    public HandlerPriority priority() {
        return HandlerPriority.TIMER;
    }
}
