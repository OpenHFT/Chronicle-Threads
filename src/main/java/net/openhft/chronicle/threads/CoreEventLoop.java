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

/**
 * Represents a core event loop interface extending {@link EventLoop} that provides additional
 * functionality for managing the running state, loop execution time, and associated thread.
 */
public interface CoreEventLoop extends EventLoop {

    /**
     * Constant indicating that the event loop is not currently executing an iteration.
     * Used as the return value for {@link #loopStartNS()} when the event loop is idle.
     */
    long NOT_IN_A_LOOP = Long.MAX_VALUE;

    /**
     * Retrieves the thread on which the event loop is running.
     *
     * @return the {@link Thread} that the event loop is associated with, or {@code null} if
     *         the event loop has not started.
     */
    Thread thread();

    /**
     * Obtains the time, in nanoseconds, at which the currently executing loop iteration started.
     * This time is derived from {@link System#nanoTime()}.
     *
     * @return the start time of the current loop iteration, or {@link #NOT_IN_A_LOOP} if the
     *         event loop is not actively executing.
     */
    long loopStartNS();

    /**
     * Dumps the current running state of the event loop along with a provided message. A final check,
     * supplied as a {@link BooleanSupplier}, can be used to conditionally verify the state.
     *
     * @param message    a custom message describing the context or reason for dumping the state
     * @param finalCheck a {@link BooleanSupplier} that performs a final conditional check before dumping
     */
    void dumpRunningState(@NotNull final String message, @NotNull final BooleanSupplier finalCheck);

    /**
     * Determines whether a specified thread is currently the one running the event loop.
     *
     * @param thread the {@link Thread} to check against the event loop's running thread
     * @return {@code true} if the provided thread is the one running the event loop;
     *         {@code false} otherwise.
     */
    boolean isRunningOnThread(Thread thread);
}
