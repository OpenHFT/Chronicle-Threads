package net.openhft.chronicle.threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public interface ExecutorFactory {
    ExecutorService acquireExecutorService(String name, int threads, boolean daemon);

    ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon);
}
