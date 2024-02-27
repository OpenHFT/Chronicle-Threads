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

public class ThreadMonitorHarness implements ThreadMonitor {
    private final ThreadHolder thread;
    private final LongSupplier timeSupplier;
    private long lastActionCall = Long.MAX_VALUE;
    private long lastStartedNS = NOT_IN_A_LOOP;

    public ThreadMonitorHarness(ThreadHolder thread, LongSupplier timeSupplier) {
        this.thread = thread;
        this.timeSupplier = timeSupplier;
    }

    public ThreadMonitorHarness(ThreadHolder thread) {
        this(thread, System::nanoTime);
    }

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
        if (actionCallDelay > thread.timingTolerance()) {
            if (thread.isAlive())
                thread.monitorThreadDelayed(actionCallDelay);
            return true;
        }
        if (!thread.shouldLog(nowNS))
            return false;
        thread.dumpThread(startedNS, nowNS);
        return false; // true assumes we are about to need to check again.
    }

    @Override
    public String toString() {
        return "ThreadMonitorHarness<" + thread.getName() + ">";
    }
}
