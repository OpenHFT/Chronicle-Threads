/*
 * Copyright 2016-2025 chronicle.software
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
