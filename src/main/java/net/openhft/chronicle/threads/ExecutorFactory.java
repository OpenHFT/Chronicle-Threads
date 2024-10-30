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
import java.util.concurrent.ScheduledExecutorService;

/**
 * A factory interface for acquiring instances of {@link ExecutorService} and {@link ScheduledExecutorService}.
 * Allows customization of executor services with specified names, thread counts, and daemon status.
 */
public interface ExecutorFactory {

    /**
     * Acquires a new {@link ExecutorService} with the specified configuration.
     *
     * @param name    the name for the executor service, useful for identifying thread groups
     * @param threads the number of threads in the executor service
     * @param daemon  if {@code true}, the executor's threads are daemon threads; otherwise, they are user threads
     * @return a configured {@link ExecutorService} instance
     */
    ExecutorService acquireExecutorService(String name, int threads, boolean daemon);

    /**
     * Acquires a new {@link ScheduledExecutorService} with the specified configuration.
     *
     * @param name   the name for the scheduled executor service, useful for identifying thread groups
     * @param daemon if {@code true}, the scheduled executor's threads are daemon threads; otherwise, they are user threads
     * @return a configured {@link ScheduledExecutorService} instance
     */
    ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon);
}
