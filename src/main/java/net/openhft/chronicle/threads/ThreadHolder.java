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

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

public interface ThreadHolder {
    int TIMING_ERROR = Jvm.getInteger("threads.timing.error", 80_000_000);

    boolean isAlive() throws InvalidEventHandlerException;

    void reportFinished();

    void resetTimers();

    /**
     * Get the {@link System#nanoTime()} at which the currently executing loop iteration started
     *
     * @return The time the current loop started, or {@link CoreEventLoop#NOT_IN_A_LOOP} if no iteration is executing
     */
    long startedNS();

    boolean shouldLog(long nowNS);

    void dumpThread(long startedNS, long nowNS);

    String getName();

    void monitorThreadDelayed(long actionCallDelayNS);

    long timingToleranceNS();
}
