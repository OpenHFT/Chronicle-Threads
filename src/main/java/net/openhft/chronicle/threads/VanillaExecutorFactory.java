/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Default {@link ExecutorFactory} used by Chronicle Threads.
 *
 * <p>It creates standard JDK executor services backed by a
 * {@link NamedThreadFactory}.  Single thread requests result in a
 * {@link java.util.concurrent.Executors#newSingleThreadExecutor single-thread}
 * pool, otherwise a fixed thread pool is returned.  Scheduled executors are
 * always single-threaded.</p>
 */
public enum VanillaExecutorFactory implements ExecutorFactory {
    /** sole instance used by default */
    INSTANCE;

    /**
     * Provides an executor backed by a {@link NamedThreadFactory}. A single
     * thread executor is created when {@code threads} equals one, otherwise a
     * fixed thread pool is returned.
     */
    @Override
    public ExecutorService acquireExecutorService(String name, int threads, boolean daemon) {
        NamedThreadFactory threadFactory = new NamedThreadFactory(name, daemon);
        return threads == 1
                ? Executors.newSingleThreadExecutor(threadFactory)
                : Executors.newFixedThreadPool(threads, threadFactory);
    }

    /**
     * Creates a single-thread {@link ScheduledExecutorService}.
     */
    @Override
    public ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon) {
        return Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory(name, daemon));
    }
}
