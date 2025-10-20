/*
 * Copyright 2016-2025 chronicle.software
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
import net.openhft.chronicle.threads.internal.ThreadMonitorHarness;
import net.openhft.chronicle.threads.internal.ThreadsThreadHolder;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public enum ThreadMonitors {
    ; // none

    /**
     * Create a monitor for a single thread.
     *
     * @param description   text used in log messages
     * @param timeLimit     threshold in nanoseconds before a stack trace is logged
     * @param timeSupplier  supplies the current time, usually {@link System#nanoTime}
     * @param threadSupplier returns the thread to observe
     * @return a monitor handler for installation on a monitor loop
     */
    public static ThreadMonitor forThread(String description, long timeLimit,
                                          LongSupplier timeSupplier,
                                          Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description,
                timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    @NotNull
    private static Consumer<String> perfOn() {
        return msg -> Jvm.perf().on(ThreadMonitor.class, msg);
    }

    /**
     * Variant of {@link #forThread(String, long, LongSupplier, Supplier)} that
     * allows the caller to control logging.
     *
     * @param description    text used in log messages
     * @param timeLimit      threshold in nanoseconds before a stack trace is logged
     * @param timeSupplier   supplies the current time
     * @param threadSupplier returns the thread to observe
     * @param logEnabled     predicate controlling whether logging occurs
     * @param logConsumer    receives the formatted log message
     * @return a monitor handler for installation on a monitor loop
     */
    public static ThreadMonitor forThread(String description, long timeLimit,
                                          LongSupplier timeSupplier,
                                          Supplier<Thread> threadSupplier,
                                          BooleanSupplier logEnabled,
                                          Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description,
                timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }

    /**
     * Create a monitor aimed at a service thread.
     *
     * @param description   text used in log messages
     * @param timeLimit     threshold in nanoseconds before a stack trace is logged
     * @param timeSupplier  supplies the current time
     * @param threadSupplier returns the thread to observe
     * @return a monitor handler for installation on a monitor loop
     */
    public static ThreadMonitor forServices(String description, long timeLimit,
                                            LongSupplier timeSupplier,
                                            Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description,
                timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    /**
     * Variant of {@link #forServices(String, long, LongSupplier, Supplier)} with
     * caller controlled logging.
     *
     * @param description    text used in log messages
     * @param timeLimit      threshold in nanoseconds before a stack trace is logged
     * @param timeSupplier   supplies the current time
     * @param threadSupplier returns the thread to observe
     * @param logEnabled     predicate controlling whether logging occurs
     * @param logConsumer    receives the formatted log message
     * @return a monitor handler for installation on a monitor loop
     */
    public static ThreadMonitor forServices(String description, long timeLimit,
                                            LongSupplier timeSupplier,
                                            Supplier<Thread> threadSupplier,
                                            BooleanSupplier logEnabled,
                                            Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description,
                timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }
}
