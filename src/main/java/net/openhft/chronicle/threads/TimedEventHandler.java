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
 * Base {@link EventHandler} that schedules itself using
 * the return value of {@link #timedAction()}.
 * <p>
 * When {@code action()} is invoked the handler checks whether the
 * current time has passed {@code nextRunNS}. If so it performs the
 * work and asks {@code timedAction()} how many micro-seconds to wait
 * before running again. A negative delay signals that the handler has
 * finished and should be removed.
 *
 * <pre>
 * class HeartbeatHandler extends TimedEventHandler {
 *     &#64;Override
 *     protected long timedAction() {
 *         sendHeartbeat();
 *         return 500_000; // run again in half a second
 *     }
 * }
 * </pre>
 */
public abstract class TimedEventHandler implements EventHandler {
    /** next scheduled run time in {@link System#nanoTime()} units. */
    private long nextRunNS = 0;

    /**
     * Executes the handler when the scheduled time has arrived.
     * <p>
     * If {@code System.nanoTime()} is greater than or equal to
     * {@code nextRunNS} the handler calls {@link #timedAction()} and
     * stores the returned delay to compute the next run time. The delay
     * is specified in micro-seconds and converted to nano-seconds. A
     * negative delay causes the method to return {@code true} so the
     * event loop can drop this handler.
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
     * Performs the timed work and specifies the delay until the next call.
     *
     * @return delay in micro-seconds. A negative value means the handler has
     * finished and {@code action()} should return {@code true}.
     */
    protected abstract long timedAction() throws InvalidEventHandlerException;

    @NotNull
    @Override
    public HandlerPriority priority() {
        return HandlerPriority.TIMER;
    }
}
