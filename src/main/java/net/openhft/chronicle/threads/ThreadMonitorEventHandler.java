package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

class ThreadMonitorEventHandler implements EventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ThreadMonitorEventHandler.class);
    private static final int TIMING_ERROR = OS.isWindows() ? 20_000_000 : 12_000_000;

    private final String description;
    private final long timeLimit;
    private final LongSupplier timeSupplier;
    private final Supplier<Thread> threadSupplier;
    private final BooleanSupplier logEnabled;
    private final Consumer<String> logConsumer;
    private long lastTime = 0, lastActionCall = Long.MAX_VALUE;

    ThreadMonitorEventHandler(String description, long timeLimit, LongSupplier timeSupplier,
                              Supplier<Thread> threadSupplier) {
        this(description, timeLimit, timeSupplier, threadSupplier, LOG::isInfoEnabled, LOG::info);
    }

    // visible for testing
    ThreadMonitorEventHandler(String description, long timeLimit, LongSupplier timeSupplier,
                              Supplier<Thread> threadSupplier, BooleanSupplier logEnabled,
                              Consumer<String> logConsumer) {
        this.description = description;
        this.timeLimit = timeLimit;
        this.timeSupplier = timeSupplier;
        this.threadSupplier = threadSupplier;
        this.logEnabled = logEnabled;
        this.logConsumer = logConsumer;
    }

    @Override
    public boolean action() {
        long time = timeSupplier.getAsLong();
        long now = System.nanoTime();
        try {
            if (time == Long.MIN_VALUE || time == Long.MAX_VALUE) {
                return false;
            }

            if (now - lastActionCall > timeLimit + TIMING_ERROR) { // < 10 ms is too common
                Thread thread = threadSupplier.get();
                if (thread != null && thread.isAlive())
                    logConsumer.accept("Monitor thread for " + thread.getName() + " was delayed by " + (now - lastActionCall) / 100000 / 10.0 + " ms");
                return true;
            }

            long latency = now - time;
            if (latency <= timeLimit) {
                return false;
            }

            Thread thread = threadSupplier.get();
            if (thread != null && thread.isAlive() && logEnabled.getAsBoolean()) {
                String type = (time == lastTime) ? "re-reporting" : "new report";
                StringBuilder out = new StringBuilder()
                        .append("THIS IS NOT AN ERROR, but a profile of the thread, ").append(description)
                        .append(" thread ").append(thread.getName())
                        .append(" interrupted ").append(thread.isInterrupted())
                        .append(" blocked for ").append(TimeUnit.NANOSECONDS.toMillis(latency))
                        .append(" ms. ").append(type);
                Jvm.trimStackTrace(out, thread.getStackTrace());
                logConsumer.accept(out.toString());

                lastTime = time;
            }
            return false;

        } finally {
            lastActionCall = now;
        }
    }

    @NotNull
    @Override
    public HandlerPriority priority() {
        return HandlerPriority.MONITOR;
    }
}