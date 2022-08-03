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

package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Deprecated(/* to be removed in x.24 */)
public class ServicesThreadHolder extends ThreadsThreadHolder {
    private static final boolean IGNORE_THREAD_MONITOR_EVENT_HANDLER = Jvm.getBoolean("ignoreThreadMonitorEventHandler");

    public ServicesThreadHolder(String description, long timeLimit, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        super(description, timeLimit, timeSupplier, threadSupplier, logEnabled, logConsumer);
    }

    @Override
    public boolean isAlive() throws InvalidEventHandlerException {
        if (IGNORE_THREAD_MONITOR_EVENT_HANDLER)
            throw new InvalidEventHandlerException("Ignoring thread monitor event handler");

        return super.isAlive();
    }

    @Override
    protected long timingError() {
        // services monitor thread is subject to greater variance
        return super.timingError() * 4;
    }
}
