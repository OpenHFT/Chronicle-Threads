package net.openhft.chronicle.threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public enum VanillaExecutorFactory implements ExecutorFactory {
    INSTANCE;

    @Override
    public ExecutorService acquireExecutorService(String name, int threads, boolean daemon) {
        NamedThreadFactory threadFactory = new NamedThreadFactory(name, daemon);
        return threads == 1
                ? Executors.newSingleThreadExecutor(threadFactory)
                : Executors.newFixedThreadPool(threads, threadFactory);
    }

    @Override
    public ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon) {
        return Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory(name, daemon));
    }
}
