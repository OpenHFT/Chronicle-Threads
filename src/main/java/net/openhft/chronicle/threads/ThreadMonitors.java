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
import net.openhft.chronicle.threads.internal.ThreadMonitorHarness;
import net.openhft.chronicle.threads.internal.ThreadsThreadHolder;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The {@code ThreadMonitors} enum provides factory methods for creating instances of {@link ThreadMonitor},
 * designed for monitoring specific threads or services with defined time limits and logging capabilities.
 * These monitors help in detecting delays and managing performance within threading contexts.
 *
 * <p>The factory methods support flexible configurations with options for custom logging and timing controls,
 * making them adaptable to various application needs.</p>
 */
public enum ThreadMonitors {
    ; // none

    /**
     * Creates a {@link ThreadMonitor} for a specific thread with a set time limit, time supplier, and thread supplier.
     * Uses default logging and performance monitoring.
     *
     * @param description   a description of the monitored thread or task
     * @param timeLimit     the maximum time limit in nanoseconds
     * @param timeSupplier  provides the current time in nanoseconds
     * @param threadSupplier supplies the {@link Thread} to be monitored
     * @return a configured {@link ThreadMonitor} instance
     */
    public static ThreadMonitor forThread(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    /**
     * Returns a {@link Consumer} for logging performance-related messages.
     *
     * @return a performance logging {@link Consumer}
     */
    @NotNull
    private static Consumer<String> perfOn() {
        return msg -> Jvm.perf().on(ThreadMonitor.class, msg);
    }

    /**
     * Creates a {@link ThreadMonitor} for a specific thread with custom logging and enabled conditions.
     *
     * @param description   a description of the monitored thread or task
     * @param timeLimit     the maximum time limit in nanoseconds
     * @param timeSupplier  provides the current time in nanoseconds
     * @param threadSupplier supplies the {@link Thread} to be monitored
     * @param logEnabled    a {@link BooleanSupplier} indicating whether logging is enabled
     * @param logConsumer   a {@link Consumer} for logging messages
     * @return a configured {@link ThreadMonitor} instance with custom logging
     */
    public static ThreadMonitor forThread(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }

    /**
     * Creates a {@link ThreadMonitor} for monitoring services, using a default performance logger.
     *
     * @param description   a description of the monitored service or task
     * @param timeLimit     the maximum time limit in nanoseconds
     * @param timeSupplier  provides the current time in nanoseconds
     * @param threadSupplier supplies the {@link Thread} to be monitored
     * @return a configured {@link ThreadMonitor} instance for services
     */
    public static ThreadMonitor forServices(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    /**
     * Creates a {@link ThreadMonitor} for services with custom logging and enabled conditions.
     *
     * @param description   a description of the monitored service or task
     * @param timeLimit     the maximum time limit in nanoseconds
     * @param timeSupplier  provides the current time in nanoseconds
     * @param threadSupplier supplies the {@link Thread} to be monitored
     * @param logEnabled    a {@link BooleanSupplier} indicating whether logging is enabled
     * @param logConsumer   a {@link Consumer} for logging messages
     * @return a configured {@link ThreadMonitor} instance with custom logging for services
     */
    public static ThreadMonitor forServices(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }
}
