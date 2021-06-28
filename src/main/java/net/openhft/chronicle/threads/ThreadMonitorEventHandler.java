/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
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

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Deprecated(/* To be removed in x.23 */)
class ThreadMonitorEventHandler extends DelegateEventHandler {

    ThreadMonitorEventHandler(String description, long timeLimit, LongSupplier timeSupplier,
                              Supplier<Thread> threadSupplier) {
        super(ThreadMonitors.forThread(description, timeLimit, timeSupplier, threadSupplier, () -> Jvm.isPerfEnabled(ThreadMonitor.class),
                msg -> Jvm.perf().on(ThreadMonitor.class, msg)));
    }

    // visible for testing
    ThreadMonitorEventHandler(String description, long timeLimit, LongSupplier timeSupplier,
                              Supplier<Thread> threadSupplier, BooleanSupplier logEnabled,
                              Consumer<String> logConsumer) {
        super(ThreadMonitors.forThread(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer));
    }
}