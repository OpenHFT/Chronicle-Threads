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

package net.openhft.chronicle.threads.internal;

import net.openhft.affinity.Affinity;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.ThreadHolder;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Helper used by {@link ThreadMonitorHarness} to monitor a service thread.
 * <p>
 * The harness polls the thread and the supplied time source. When the
 * thread appears to be blocked for longer than the configured limit the
 * stack trace is logged via {@link #logConsumer} if {@link #logEnabled} is
 * true.
 * </p>
 */
public class ThreadsThreadHolder implements ThreadHolder {
    private final String description;
    private final long timeLimitNS;
    private final LongSupplier timeSupplier;
    private final Supplier<Thread> threadSupplier;
    /**
     * Allows logging to be enabled or disabled at run time.
     */
    private final BooleanSupplier logEnabled;
    /**
     * Receives formatted log messages.
     */
    private final Consumer<String> logConsumer;
    private long lastTime = 0;

    /**
     * Create an instance configured to monitor the supplied thread.
     *
     * @param description    text appended to log messages
     * @param timeLimitNS    threshold in nanoseconds before logging occurs
     * @param timeSupplier   provides the current time
     * @param threadSupplier supplies the thread to observe
     * @param logEnabled     predicate controlling whether logging happens
     * @param logConsumer    receives the formatted log message
     */
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
        double delayedMs = Math.round((actionCallDelayNS / 1_000_000.0) * 10.0) / 10.0;
        logConsumer.accept("Monitor thread for " + getName() + " cpuId: " + Affinity.getCpu() + " was delayed by " + delayedMs + " ms");
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
                .append(" blocked for ").append(nanosecondsToMillisWithTenthsPrecision(latencyNS))
                .append(" ms. ").append(type);
        Jvm.trimStackTrace(out, thread.getStackTrace());
        logConsumer.accept(out.toString());

        lastTime = startedNS;
    }

    /**
     * Results in a double that retains only it's 1/10ths precision
     *
     * @param timeInNS The time in nanoseconds
     * @return The time in milliseconds represented as a float with limited precision
     */
    static double nanosecondsToMillisWithTenthsPrecision(long timeInNS) {
        double millis = timeInNS / 1_000_000.0;
        return Math.round(millis * 10.0) / 10.0;
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
