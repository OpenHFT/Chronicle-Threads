/*
 * Copyright 2016-2020 chronicle.software
 *
 *       https://chronicle.software
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A singleton factory class for creating instances of {@link ExecutorService} and {@link ScheduledExecutorService}.
 * This class implements the {@link ExecutorFactory} interface and provides methods to acquire either a fixed-thread
 * pool or single-thread executor, as well as a single-thread scheduled executor. The threads created by this factory
 * can be named and configured as daemon threads.
 */
public enum VanillaExecutorFactory implements ExecutorFactory {
    INSTANCE;

    /**
     * Creates an {@link ExecutorService} with a specified number of threads and a custom naming convention for
     * each thread. If only one thread is specified, a single-thread executor is created; otherwise, a fixed
     * thread pool is created with the given number of threads.
     *
     * @param name    the base name to assign to threads created by this executor
     * @param threads the number of threads to create; if {@code threads} is 1, a single-thread executor is returned
     * @param daemon  if {@code true}, each thread in the executor will be created as a daemon thread
     * @return an {@link ExecutorService} with the specified configuration
     */
    @Override
    public ExecutorService acquireExecutorService(String name, int threads, boolean daemon) {
        NamedThreadFactory threadFactory = new NamedThreadFactory(name, daemon);
        return threads == 1
                ? Executors.newSingleThreadExecutor(threadFactory)
                : Executors.newFixedThreadPool(threads, threadFactory);
    }

    /**
     * Creates a {@link ScheduledExecutorService} with a single-thread executor and a custom naming convention.
     * This method allows for the scheduling of tasks with a single background thread that can be configured as a
     * daemon thread if needed.
     *
     * @param name   the name assigned to the single thread in this scheduled executor
     * @param daemon if {@code true}, the thread will be created as a daemon thread
     * @return a {@link ScheduledExecutorService} configured with a single thread
     */
    @Override
    public ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon) {
        return Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory(name, daemon));
    }
}
