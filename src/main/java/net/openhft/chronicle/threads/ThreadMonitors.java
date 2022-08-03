/*
 * Copyright 2016-2022 chronicle.software
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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.threads.internal.ThreadMonitorHarness;
import net.openhft.chronicle.threads.internal.ThreadsThreadHolder;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public enum ThreadMonitors {
    ; // none

    public static ThreadMonitor forThread(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    @NotNull
    private static Consumer<String> perfOn() {
        return msg -> Jvm.perf().on(ThreadMonitor.class, msg);
    }

    public static ThreadMonitor forThread(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }

    public static ThreadMonitor forServices(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, () -> true, perfOn()));
    }

    public static ThreadMonitor forServices(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        return new ThreadMonitorHarness(new ThreadsThreadHolder(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }
}
