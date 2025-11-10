//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//

package net.openhft.chronicle.threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Strategy interface for obtaining {@link ExecutorService} instances.
 *
 * <p>The Chronicle Threads utility relies on this abstraction so that
 * applications may plug in their own executor creation logic.  The
 * supplied implementation can integrate with alternative concurrency
 * frameworks or simply wrap the standard JDK executors.</p>
 */
public interface ExecutorFactory {

    /**
     * Creates or retrieves an {@link ExecutorService}.
     *
     * @param name    base name for the threads created by the executor
     * @param threads requested thread count
     * @param daemon  {@code true} if the threads should be daemon threads
     * @return a service suitable for running general tasks
     */
    ExecutorService acquireExecutorService(String name, int threads, boolean daemon);

    /**
     * Creates or retrieves a {@link ScheduledExecutorService}.
     *
     * @param name   base name for the threads created by the scheduler
     * @param daemon {@code true} if the threads should be daemon threads
     * @return a single-threaded scheduler
     */
    ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon);
}
