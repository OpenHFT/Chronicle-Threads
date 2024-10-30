/*
 * Copyright 2016-2022 chronicle.software
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

package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.ThreadHolder;
import net.openhft.chronicle.threads.ThreadMonitor;

import java.util.function.LongSupplier;

import static net.openhft.chronicle.threads.CoreEventLoop.NOT_IN_A_LOOP;

/**
 * A harness for monitoring and managing the lifecycle and timing of a {@link ThreadHolder} instance.
 * <p>
 * The {@code ThreadMonitorHarness} ensures that threads follow expected timing constraints and handles
 * any necessary logging, monitoring, and reporting in cases of delays.
 */
public class ThreadMonitorHarness implements ThreadMonitor {
    private final ThreadHolder thread;
    private final LongSupplier timeSupplier;
    private long lastActionCall = Long.MAX_VALUE;
    private long lastStartedNS = NOT_IN_A_LOOP;

    /**
     * Constructs a {@code ThreadMonitorHarness} to monitor a specified {@link ThreadHolder}.
     *
     * @param thread       the thread holder to be monitored
     * @param timeSupplier a supplier providing the current time in nanoseconds
     */
    public ThreadMonitorHarness(ThreadHolder thread, LongSupplier timeSupplier) {
        this.thread = thread;
        this.timeSupplier = timeSupplier;
    }

    /**
     * Constructs a {@code ThreadMonitorHarness} using the default system time supplier.
     *
     * @param thread the thread holder to be monitored
     */
    public ThreadMonitorHarness(ThreadHolder thread) {
        this(thread, System::nanoTime);
    }

    /**
     * Monitors the thread's activity and checks for timing violations.
     * <p>
     * This method verifies if the thread is alive and monitors delays in action calls.
     * If delays exceed a defined tolerance, it triggers appropriate logging and handling.
     *
     * @return {@code false} if the thread timing is within tolerance, otherwise {@code true}
     * @throws InvalidEventHandlerException if the thread has finished or is no longer valid
     */
    @Override
    public boolean action() throws InvalidEventHandlerException {
        if (!thread.isAlive()) {
            thread.reportFinished();
            throw new InvalidEventHandlerException();
        }
        long startedNS = thread.startedNS();
        long nowNS = timeSupplier.getAsLong();

        // Record lastActionCall time on every call to prevent false-positive "monitorThreadDelayed" reports
        long actionCallDelay = nowNS - this.lastActionCall;
        this.lastActionCall = nowNS;

        if (startedNS == 0 || startedNS == NOT_IN_A_LOOP) {
            return false;
        }
        if (startedNS != lastStartedNS) {
            thread.resetTimers();
            lastStartedNS = startedNS;
        }
        if (actionCallDelay > thread.timingToleranceNS()) {
            if (thread.isAlive())
                thread.monitorThreadDelayed(actionCallDelay);
            return true;
        }
        if (!thread.shouldLog(nowNS))
            return false;
        thread.dumpThread(startedNS, nowNS);
        return false; // true assumes we are about to need to check again.
    }

    /**
     * Provides a string representation of the {@code ThreadMonitorHarness}, including the monitored thread name.
     *
     * @return a string representation of this monitor harness
     */
    @Override
    public String toString() {
        return "ThreadMonitorHarness<" + thread.getName() + ">";
    }
}
