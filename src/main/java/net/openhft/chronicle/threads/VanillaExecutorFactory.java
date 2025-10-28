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
    /**
     * sole instance used by default
     */
    INSTANCE;

    @Override
    /**
     * Provides an executor backed by a {@link NamedThreadFactory}.  A single
     * thread executor is created when {@code threads} equals one, otherwise a
     * fixed thread pool is returned.
     */
    public ExecutorService acquireExecutorService(String name, int threads, boolean daemon) {
        NamedThreadFactory threadFactory = new NamedThreadFactory(name, daemon);
        return threads == 1
                ? Executors.newSingleThreadExecutor(threadFactory)
                : Executors.newFixedThreadPool(threads, threadFactory);
    }

    @Override
    /**
     * Creates a single-thread {@link ScheduledExecutorService}.
     */
    public ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon) {
        return Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory(name, daemon));
    }
}
