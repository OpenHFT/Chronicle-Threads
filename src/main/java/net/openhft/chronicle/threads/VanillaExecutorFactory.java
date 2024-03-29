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
        return Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory(name, daemon));
    }
}
