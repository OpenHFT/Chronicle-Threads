package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.ThreadHolder;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class ThreadsThreadHolder implements ThreadHolder {
    private static final int TIMING_ERROR = OS.isWindows() ? 20_000_000 : 12_000_000;
    private final String description;
    private final long timeLimit;
    private final LongSupplier timeSupplier;
    private final Supplier<Thread> threadSupplier;
    private final BooleanSupplier logEnabled;
    private final Consumer<String> logConsumer;
    private long lastTime = 0;

    public ThreadsThreadHolder(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        this.description = description;
        this.timeLimit = timeLimit;
        this.timeSupplier = timeSupplier;
        this.threadSupplier = threadSupplier;
        this.logEnabled = logEnabled;
        this.logConsumer = logConsumer;
    }

    @Override
    public boolean isAlive() throws InvalidEventHandlerException {
        return threadSupplier.get().isAlive();
    }

    @Override
    public void resetTimers() {
        // nothing to do.
    }

    @Override
    public void reportFinished() {
        // assumes it never dies??
    }

    @Override
    public long startedNS() {
        return timeSupplier.getAsLong();
    }

    @Override
    public void monitorThreadDelayed(long actionCallDelayNS) {
        logConsumer.accept("Monitor thread for " + getName() + " was delayed by " + actionCallDelayNS / 100000 / 10.0 + " ms");
    }

    @Override
    public boolean shouldLog(long nowNS) {
        return nowNS - startedNS() > timeLimit
                && logEnabled.getAsBoolean();
    }

    @Override
    public void dumpThread(long startedNS, long nowNS) {
        long latency = nowNS - startedNS;
        Thread thread = threadSupplier.get();

        String type = (startedNS == lastTime) ? "re-reporting" : "new report";
        StringBuilder out = new StringBuilder()
                .append("THIS IS NOT AN ERROR, but a profile of the thread, ").append(description)
                .append(" thread ").append(thread.getName())
                .append(" interrupted ").append(thread.isInterrupted())
                .append(" blocked for ").append(latency / 100000 / 10.0)
                .append(" ms. ").append(type);
        Jvm.trimStackTrace(out, thread.getStackTrace());
        logConsumer.accept(out.toString());

        lastTime = startedNS;
    }

    @Override
    public long timingTolerance() {
        return timeLimit + timingError();
    }

    protected long timingError() {
        return TIMING_ERROR;
    }

    @Override
    public String getName() {
        return threadSupplier.get().getName();
    }
}
