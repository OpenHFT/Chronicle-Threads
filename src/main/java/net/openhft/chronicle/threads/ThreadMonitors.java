/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
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
 * Factory for {@link ThreadMonitor} instances used to watch long-running threads.
 * <p>
 * The helpers build monitors around a supplied {@link Thread}, time source and threshold,
 * and arrange for stack traces to be logged when the thread appears blocked or stalled
 * for longer than the configured limit. Separate variants are provided for generic and
 * service threads, with optional call sites for custom logging behaviour.
 */
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
