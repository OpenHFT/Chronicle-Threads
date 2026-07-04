/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.util.IgnoresEverything;
import net.openhft.chronicle.wire.ServicesTimestampLongConverter;
import net.openhft.chronicle.wire.metrics.CounterInstrument;
import net.openhft.chronicle.wire.metrics.GaugeInstrument;
import net.openhft.chronicle.wire.metrics.LatencyInstrument;
import net.openhft.chronicle.wire.metrics.Metrics;
import net.openhft.chronicle.wire.metrics.MetricsOut;
import net.openhft.chronicle.wire.metrics.MetricsRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owner-flush metrics wiring for a core event loop, recorded on the loop thread and flushed
 * by the loop itself at a configurable cadence. Each loop owns an <em>independent</em>
 * registry ({@link Metrics#newRegistry(String)}) holding exactly its own instruments, all
 * registered under the source {@value #SOURCE} and labelled {@code loop=&lt;name&gt;} (plus an
 * optional {@code service} label, see below), so a loop's flush emits only that loop's
 * series and never another loop's. The registry is {@linkplain #close() closed} when the
 * loop closes, deregistering the instruments so a dead loop's series stop being emitted.
 * <p>
 * Instruments, all derived from counters the loop (or this class, on the loop's behalf)
 * already maintains per iteration:
 * <ul>
 *     <li>{@code chronicle_threads_eventloop_task_latency_ns} - histogram of busy-iteration
 *     durations (the time the handlers ran and reported work done);</li>
 *     <li>{@code chronicle_threads_eventloop_busy_ratio} - fraction of the flush window spent
 *     inside busy iterations;</li>
 *     <li>{@code chronicle_threads_eventloop_idle_ratio} - fraction of the flush window
 *     measured as idle: non-busy iteration durations plus the gaps between iterations
 *     (pauser waits, timer/daemon handling). A healthy loop shows
 *     {@code busy_ratio + idle_ratio ~= 1}; a shortfall means loop-thread time is
 *     unaccounted for (e.g. the window ended inside a long stall), so stalls are visible
 *     rather than hidden by deriving one ratio from the other;</li>
 *     <li>{@code chronicle_threads_eventloop_iterations_total} - iteration counter; the
 *     emitted per-flush delta is the iteration count of that window, so a spinning-but-idle
 *     or a stalled loop is distinguishable from a busy one.</li>
 * </ul>
 * <p>
 * <b>Late binding (startup-only).</b> The source is resolved <em>once</em>, at loop
 * construction, via {@link Metrics#forSourceStatic(String)}: install the
 * {@link net.openhft.chronicle.wire.metrics.MetricsBinding} <em>before</em> constructing the
 * event loop. A binding installed later is deliberately not observed - this is the hot-path
 * library-instrumentation policy: a disabled loop pays one final-boolean check per iteration
 * and nothing else, and the enabled recording path is allocation-free ({@link #onIteration}
 * is plain field arithmetic plus a zero-allocation histogram sample; flushing re-emits reused
 * DTOs).
 * <p>
 * <b>Which loops are instrumented.</b> Only {@link net.openhft.chronicle.threads.MediumEventLoop}
 * and its subclass {@link net.openhft.chronicle.threads.VanillaEventLoop} are instrumented:
 * they share the single {@code runLoop} iteration seam this class hooks (VanillaEventLoop
 * adds timer/daemon queues but inherits the loop body). The remaining implementations are
 * intentionally not instrumented:
 * <ul>
 *     <li>{@link net.openhft.chronicle.threads.BlockingEventLoop} has no iteration seam to
 *     sample - each handler runs blocking on its own dedicated thread, so there is no loop
 *     cadence, busy ratio or iteration count to report;</li>
 *     <li>{@link net.openhft.chronicle.threads.MonitorEventLoop} is the low-frequency
 *     housekeeping loop (parked most of the time, running MONITOR handlers that watch the
 *     core loops); instrumenting it would add series of no latency value while the loops it
 *     monitors are covered above.</li>
 * </ul>
 * <p>
 * <b>Configuration.</b> The flush cadence defaults to 1 s and is read from the system
 * property {@value #FLUSH_INTERVAL_MS_PROPERTY} (milliseconds) at loop construction; a zero
 * or negative value disables event-loop metrics entirely (with a single {@link Jvm#warn()}
 * per JVM) rather than busy-flushing every iteration. When the system property
 * {@value #SERVICE_LABEL_PROPERTY} is set and non-empty, its value is added as a
 * {@code service} label on every instrument (read once at loop construction). The cadence is
 * driven by the loop's own time source, {@link System#nanoTime()} - the same clock the loop
 * already stamps {@code loopStartNS} with.
 */
public final class EventLoopMetrics {

    /**
     * The dotted source name event-loop instruments are registered under.
     */
    public static final String SOURCE = "chronicle.threads.eventloop";

    /**
     * System property naming the flush cadence in milliseconds; read at loop construction.
     * Zero or negative disables event-loop metrics (one warning per JVM).
     */
    public static final String FLUSH_INTERVAL_MS_PROPERTY = "chronicle.threads.metrics.flush.interval.ms";

    /**
     * System property naming an optional {@code service} label applied to every event-loop
     * instrument when set and non-empty; read at loop construction.
     */
    public static final String SERVICE_LABEL_PROPERTY = "chronicle.metrics.service";

    static final long DEFAULT_FLUSH_INTERVAL_MS = 1_000L;

    // One warning per JVM for a disabling (zero/negative) flush interval.
    private static final AtomicBoolean WARNED_BAD_INTERVAL = new AtomicBoolean();
    // One warning per JVM for a throwing sink; loop execution and existing debug paths continue.
    private static final AtomicBoolean WARNED_SINK_FAILURE = new AtomicBoolean();

    private final MetricsOut out;
    // Per-loop registry: this loop's flush touches only this loop's instruments (owner-flush).
    private final MetricsRegistry registry;
    private final LatencyInstrument taskLatency;
    private final GaugeInstrument busyRatio;
    private final GaugeInstrument idleRatio;
    private final CounterInstrument iterations;
    private final long flushIntervalNs;

    // Window state; loop-thread confined, plain fields.
    private long busyNs;
    private long idleNs;
    private long lastEndNs;
    private long windowStartNs;
    private long nextFlushNs;

    private EventLoopMetrics(final String loopName, final MetricsOut out, final long flushIntervalMs) {
        this.out = out;
        this.registry = Metrics.newRegistry(SOURCE);
        // Labels passed at registration so they form the dedup identity within this registry.
        final String labels = labelsFor(loopName);
        this.taskLatency = registry.latency("chronicle_threads_eventloop_task_latency_ns", labels);
        this.busyRatio = registry.gauge("chronicle_threads_eventloop_busy_ratio", labels);
        this.idleRatio = registry.gauge("chronicle_threads_eventloop_idle_ratio", labels);
        this.iterations = registry.counter("chronicle_threads_eventloop_iterations_total", labels);
        this.flushIntervalNs = flushIntervalMs * 1_000_000L;
    }

    /**
     * Returns the metrics wiring for a loop, or {@code null} when disabled - either because
     * {@value #SOURCE} resolves to an {@link IgnoresEverything} handler (no binding installed
     * for it at construction; late installs are not observed, see the class javadoc) or
     * because {@value #FLUSH_INTERVAL_MS_PROPERTY} is zero or negative. The caller caches the
     * null check in a final boolean so a disabled loop skips all metrics work for its
     * lifetime.
     *
     * @param loopName the loop name, applied as the {@code loop} label on every instrument
     * @return the wiring, or {@code null} when metrics are disabled for this loop
     */
    @Nullable
    public static EventLoopMetrics createIfEnabled(final String loopName) {
        // Late-binding policy: hot-path instrumentation resolves once, at construction.
        final MetricsOut out = Metrics.forSourceStatic(SOURCE);
        if (out instanceof IgnoresEverything)
            return null;
        final long flushIntervalMs = Jvm.getLong(FLUSH_INTERVAL_MS_PROPERTY, DEFAULT_FLUSH_INTERVAL_MS);
        if (flushIntervalMs <= 0) {
            if (WARNED_BAD_INTERVAL.compareAndSet(false, true))
                Jvm.warn().on(EventLoopMetrics.class,
                        FLUSH_INTERVAL_MS_PROPERTY + "=" + flushIntervalMs
                                + " is zero or negative; event-loop metrics disabled."
                                + " Set a positive interval (default " + DEFAULT_FLUSH_INTERVAL_MS + " ms) to enable them.");
            return null;
        }
        return new EventLoopMetrics(loopName, out, flushIntervalMs);
    }

    // Builds the registration-time labels: loop=<name> plus optional service=<value>.
    // Values are sanitized, never thrown on: metrics must not break loop construction.
    private static String labelsFor(final String loopName) {
        final StringBuilder sb = new StringBuilder(32);
        sb.append("loop=").append(sanitizeLabelValue(loopName));
        final String service = Jvm.getProperty(SERVICE_LABEL_PROPERTY);
        if (service != null && !service.isEmpty())
            sb.append(";service=").append(sanitizeLabelValue(service));
        return sb.toString();
    }

    // '=' and ';' are the structural characters of the concatenated labels form.
    private static String sanitizeLabelValue(final String value) {
        if (value == null || value.isEmpty())
            return "unnamed";
        return value.replace('=', '_').replace(';', '_');
    }

    /**
     * Opens the first aggregation window; called once by the loop thread before its first
     * iteration.
     *
     * @param nowNs the loop's current {@link System#nanoTime()}
     */
    public void loopStarted(final long nowNs) {
        busyNs = 0;
        idleNs = 0;
        lastEndNs = nowNs;
        windowStartNs = nowNs;
        nextFlushNs = nowNs + flushIntervalNs;
    }

    /**
     * Records one loop iteration and flushes if the cadence has elapsed; called by the loop
     * thread after the handlers have run. Steady-state allocation-free: plain long
     * arithmetic, a zero-allocation histogram sample when busy, and a counter increment.
     * <p>
     * Time accounting: a busy iteration's duration goes to the busy bucket (and the latency
     * histogram); a non-busy iteration's duration, and the gap since the previous iteration
     * ended (pauser waits, timer/daemon handlers), go to the idle bucket.
     *
     * @param startNs the {@link System#nanoTime()} taken at the start of the iteration
     * @param busy    whether any handler reported work done this iteration
     */
    public void onIteration(final long startNs, final boolean busy) {
        final long endNs = System.nanoTime();
        iterations.inc();
        final long gapNs = startNs - lastEndNs;
        if (gapNs > 0)
            idleNs += gapNs;
        final long durationNs = endNs - startNs;
        if (busy) {
            busyNs += durationNs;
            taskLatency.record(durationNs);
        } else {
            idleNs += durationNs;
        }
        lastEndNs = endNs;
        if (endNs - nextFlushNs >= 0)
            flush(endNs);
    }

    /**
     * Flushes the final, partial window; called by the loop thread as the loop terminates.
     */
    public void loopFinished() {
        if (windowStartNs != 0)
            flush(System.nanoTime());
    }

    /**
     * Closes this loop's registry, deregistering its instruments so the loop's series stop
     * being emitted; called when the owning event loop closes. Idempotent; safe to race with
     * a final flush on the loop thread (a flush on a closed registry is a no-op).
     */
    public void close() {
        registry.close();
    }

    /**
     * Clears the once-per-JVM zero/negative-interval warning gate; for tests only.
     */
    public static void resetFlushIntervalWarningForTesting() {
        WARNED_BAD_INTERVAL.set(false);
    }

    private void flush(final long nowNs) {
        long windowNs = nowNs - windowStartNs;
        if (windowNs <= 0)
            windowNs = 1;
        busyRatio.set(clampRatio((double) busyNs / windowNs));
        idleRatio.set(clampRatio((double) idleNs / windowNs));
        final long eventTime = ServicesTimestampLongConverter.currentTime();
        try {
            registry.flush(out, eventTime, windowNs);
        } catch (Throwable t) {
            if (WARNED_SINK_FAILURE.compareAndSet(false, true))
                Jvm.warn().on(EventLoopMetrics.class,
                        "Event-loop metrics sink threw; dropping this window and further sink failures", t);
            // Advance the instruments' windows so a disabled/failed interval is not replayed later.
            registry.flush(Metrics.ignored(), eventTime, windowNs);
        }
        busyNs = 0;
        idleNs = 0;
        windowStartNs = nowNs;
        nextFlushNs = nowNs + flushIntervalNs;
    }

    private static double clampRatio(final double ratio) {
        if (ratio < 0.0)
            return 0.0;
        return ratio > 1.0 ? 1.0 : ratio;
    }
}
