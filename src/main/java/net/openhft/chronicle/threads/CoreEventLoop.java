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

import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

public interface CoreEventLoop extends EventLoop {

    /**
     * The value returned for {@link #loopStartNS()} when the event loop is not currently
     * executing an iteration
     */
    long NOT_IN_A_LOOP = Long.MAX_VALUE;

    /**
     * @return thread that the event loop is running on. Will be null if the event loop has not started
     */
    Thread thread();

    /**
     * Get the {@link System#nanoTime()} at which the currently executing loop iteration started
     *
     * @return The time the current loop started, or {@link #NOT_IN_A_LOOP} if no iteration is executing
     */
    long loopStartNS();

    void dumpRunningState(@NotNull final String message, @NotNull final BooleanSupplier finalCheck);
}
