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

/**
 * A class that implements {@link ThreadHolder} for monitoring and logging thread activity
 * over time. The {@code ThreadsThreadHolder} is designed to detect and report delays and
 * prolonged blocking conditions in monitored threads.
 */
public class ThreadsThreadHolder implements ThreadHolder {
    private final String description;
    private final long timeLimitNS;
    private final LongSupplier timeSupplier;
    private final Supplier<Thread> threadSupplier;
    private final BooleanSupplier logEnabled;
    private final Consumer<String> logConsumer;
    private long lastTime = 0;

    /**
     * Creates a {@code ThreadsThreadHolder} instance with the specified parameters.
     *
     * @param description    a textual description of the thread being monitored
     * @param timeLimitNS    the maximum permissible time in nanoseconds before logging occurs
     * @param timeSupplier   a supplier providing the current time in nanoseconds
     * @param threadSupplier a supplier providing the thread instance to be monitored
     * @param logEnabled     a supplier indicating if logging is currently enabled
     * @param logConsumer    a consumer to handle logging output
     */
    public ThreadsThreadHolder(String description, long timeLimitNS, LongSupplier timeSupplier,
                               Supplier<Thread> threadSupplier, BooleanSupplier logEnabled,
                               Consumer<String> logConsumer) {
        this.description = description;
        this.timeLimitNS = timeLimitNS;
        this.timeSupplier = timeSupplier;
        this.threadSupplier = threadSupplier;
        this.logEnabled = logEnabled;
        this.logConsumer = logConsumer;
    }

    /**
     * Checks if the monitored thread is alive.
     *
     * @return {@code true} if the thread is alive, {@code false} otherwise
     * @throws InvalidEventHandlerException if the thread is invalid or cannot be checked
     */
    @Override
    public boolean isAlive() throws InvalidEventHandlerException {
        return threadSupplier.get().isAlive();
    }

    /**
     * Resets internal timers. This implementation does not require any specific actions.
     */
    @Override
    public void resetTimers() {
        // nothing to do.
    }

    /**
     * Reports that the monitored thread has finished execution.
     * <p>
     * This implementation assumes the thread does not terminate under normal conditions.
     */
    @Override
    public void reportFinished() {
        // assumes it never dies??
    }

    /**
     * Retrieves the time at which the current loop iteration started, in nanoseconds.
     *
     * @return the start time of the current iteration in nanoseconds
     */
    @Override
    public long startedNS() {
        return timeSupplier.getAsLong();
    }

    /**
     * Logs a message if the monitored thread has been delayed beyond the acceptable limit.
     *
     * @param actionCallDelayNS the delay in nanoseconds since the last action call
     */
    @Override
    public void monitorThreadDelayed(long actionCallDelayNS) {
        logConsumer.accept("Monitor thread for " + getName() + " cpuId: " + Affinity.getCpu() + " was delayed by " + actionCallDelayNS / 100000 / 10.0 + " ms");
    }

    /**
     * Determines if the monitored thread should log its state based on timing thresholds.
     *
     * @param nowNS the current time in nanoseconds
     * @return {@code true} if the time since last start exceeds {@code timeLimitNS} and logging is enabled
     */
    @Override
    public boolean shouldLog(long nowNS) {
        return nowNS - startedNS() > timeLimitNS
                && logEnabled.getAsBoolean();
    }

    /**
     * Dumps the current state and stack trace of the monitored thread.
     *
     * @param startedNS the time the thread began execution, in nanoseconds
     * @param nowNS     the current time in nanoseconds
     */
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
     * Converts a time value from nanoseconds to milliseconds with one decimal precision.
     *
     * @param timeInNS the time in nanoseconds
     * @return the time in milliseconds as a double, retaining tenths precision
     */
    @SuppressWarnings(/* we mean to do the integer division first */
            {"java:S2184", "IntegerDivisionInFloatingPointContext"})
    static double nanosecondsToMillisWithTenthsPrecision(long timeInNS) {
        return (timeInNS / 100_000) / 10d;
    }

    /**
     * Returns the permissible tolerance in nanoseconds for timing deviations in the monitored thread.
     *
     * @return the allowable timing tolerance in nanoseconds
     */
    @Override
    public long timingToleranceNS() {
        return timeLimitNS + timingErrorNS();
    }

    /**
     * Returns an additional timing error margin in nanoseconds.
     *
     * @return a fixed error margin in nanoseconds
     */
    protected long timingErrorNS() {
        return TIMING_ERROR;
    }

    /**
     * Retrieves the name of the monitored thread.
     *
     * @return the thread's name, or "null" if the thread is unavailable
     */
    @Override
    public String getName() {
        Thread thread = threadSupplier.get();
        return thread == null ? "null" : thread.getName();
    }
}
