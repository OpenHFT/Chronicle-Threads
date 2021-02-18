package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class ServicesThreadHolder extends ThreadsThreadHolder {
    private static final boolean IGNORE_THREAD_MONITOR_EVENT_HANDLER = Jvm.getBoolean("ignoreThreadMonitorEventHandler");

    public ServicesThreadHolder(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        super(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer);
    }

    @Override
    public boolean isAlive() throws InvalidEventHandlerException {
        if (IGNORE_THREAD_MONITOR_EVENT_HANDLER)
            throw new InvalidEventHandlerException("Ignoring thread monitor event handler");

        return super.isAlive();
    }

    @Override
    protected long timingError() {
        // services monitor thread is subject to greater variance
        return super.timingError() * 4;
    }
}
