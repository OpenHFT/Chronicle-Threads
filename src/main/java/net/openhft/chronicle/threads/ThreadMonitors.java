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

    public static ThreadMonitor forThread(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    @NotNull
    private static Consumer<String> perfOn() {
        return msg -> Jvm.perf().on(ThreadMonitor.class, msg);
    }

    public static ThreadMonitor forThread(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }

    public static ThreadMonitor forServices(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    public static ThreadMonitor forServices(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }
}
