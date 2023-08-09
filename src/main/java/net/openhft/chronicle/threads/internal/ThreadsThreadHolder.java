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

import net.openhft.affinity.Affinity;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.ThreadHolder;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static net.openhft.chronicle.threads.internal.NanosecondDurationRenderer.renderNanosecondDuration;

public class ThreadsThreadHolder implements ThreadHolder {
    private final String description;
    private final long timeLimitNS;
    private final LongSupplier timeSupplier;
    private final Supplier<Thread> threadSupplier;
    private final BooleanSupplier logEnabled;
    private final Consumer<String> logConsumer;
    private long lastTime = 0;

    public ThreadsThreadHolder(String description, long timeLimitNS, LongSupplier timeSupplier, Supplier<Thread> threadSupplier, BooleanSupplier logEnabled, Consumer<String> logConsumer) {
        this.description = description;
        this.timeLimitNS = timeLimitNS;
        this.timeSupplier = timeSupplier;
        this.threadSupplier = threadSupplier;
        this.logEnabled = logEnabled;
        this.logConsumer = logConsumer;
    }

    @Override
    public boolean isAlive() throws InvalidEventHandlerException {
        return threadSupplier.get().isAlive();
    }

    @Override
    public void resetTimers() {
        // nothing to do.
    }

    @Override
    public void reportFinished() {
        // assumes it never dies??
    }

    @Override
    public long startedNS() {
        return timeSupplier.getAsLong();
    }

    @Override
    public void monitorThreadDelayed(long actionCallDelayNS) {
        logConsumer.accept("Monitor thread for " + getName() + " cpuId: " + Affinity.getCpu() + " was delayed by " + actionCallDelayNS / 100000 / 10.0 + " ms");
    }

    @Override
    public boolean shouldLog(long nowNS) {
        return nowNS - startedNS() > timeLimitNS
                && logEnabled.getAsBoolean();
    }

    @Override
    public void dumpThread(long startedNS, long nowNS) {
        long latencyNS = nowNS - startedNS;
        Thread thread = threadSupplier.get();

        String type = (startedNS == lastTime) ? "re-reporting" : "new report";
        StringBuilder out = new StringBuilder()
                .append("THIS IS NOT AN ERROR, but a profile of the thread, ").append(description)
                .append(" thread ").append(thread.getName())
                .append(" interrupted ").append(thread.isInterrupted())
                .append(" blocked for ").append(renderNanosecondDuration(latencyNS))
                .append(". ").append(type);
        Jvm.trimStackTrace(out, thread.getStackTrace());
        logConsumer.accept(out.toString());

        lastTime = startedNS;
    }

    @Override
    public long timingToleranceNS() {
        return timeLimitNS + timingErrorNS();
    }

    protected long timingErrorNS() {
        return TIMING_ERROR;
    }

    @Override
    public String getName() {
        Thread thread = threadSupplier.get();
        return thread == null ? "null" : thread.getName();
    }
}
