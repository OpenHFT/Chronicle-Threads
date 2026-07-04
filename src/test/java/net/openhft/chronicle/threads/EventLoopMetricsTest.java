/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.threads.internal.EventLoopMetrics;
import net.openhft.chronicle.wire.metrics.CounterMetric;
import net.openhft.chronicle.wire.metrics.GaugeMetric;
import net.openhft.chronicle.wire.metrics.HistogramMetric;
import net.openhft.chronicle.wire.metrics.Metric;
import net.openhft.chronicle.wire.metrics.Metrics;
import net.openhft.chronicle.wire.metrics.MetricsOut;
import net.openhft.chronicle.wire.metrics.PointEvent;
import net.openhft.chronicle.wire.metrics.RateMetric;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the owner-flush event-loop metrics wiring: with a capturing {@link MetricsOut}
 * installed via a binding <em>before</em> loop construction, a running
 * {@link MediumEventLoop} emits a task-latency histogram, busy/idle ratio gauges and an
 * iteration counter under the source {@code chronicle.threads.eventloop}, each loop flushing
 * only its own per-loop registry; a closed loop's series stop; a zero/negative flush interval
 * disables metrics with a single warning; and the per-iteration recording path is
 * allocation-free.
 */
class EventLoopMetricsTest extends ThreadsTestCommon {

    private static final String TASK_LATENCY_NAME = "chronicle_threads_eventloop_task_latency_ns";
    private static final String BUSY_RATIO_NAME = "chronicle_threads_eventloop_busy_ratio";
    private static final String IDLE_RATIO_NAME = "chronicle_threads_eventloop_idle_ratio";
    private static final String ITERATIONS_NAME = "chronicle_threads_eventloop_iterations_total";

    @AfterEach
    void resetMetrics() {
        Metrics.resetForTesting();
        System.clearProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY);
        System.clearProperty(EventLoopMetrics.SERVICE_LABEL_PROPERTY);
    }

    @Test
    void loopEmitsAllFourInstrumentsThroughInstalledBinding() {
        // Fast cadence so the test does not wait for the 1 s default; read at loop construction.
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "50");
        final CapturingMetricsOut capture = new CapturingMetricsOut();
        Metrics.install(source -> EventLoopMetrics.SOURCE.equals(source) ? capture : null);

        final String loopName = "metrics-capture-loop";
        try (MediumEventLoop eventLoop = new MediumEventLoop(null, loopName, Pauser.balanced(), true, null)) {
            final AtomicInteger remaining = new AtomicInteger(500);
            eventLoop.addHandler(() -> remaining.getAndDecrement() > 0);
            eventLoop.start();

            Waiters.waitForCondition("all four instruments flushed",
                    () -> capture.histogramWithSamples(loopName) != null
                            && capture.firstGauge(loopName, BUSY_RATIO_NAME) != null
                            && capture.firstGauge(loopName, IDLE_RATIO_NAME) != null
                            && capture.firstCounter(loopName) != null,
                    5_000);
        }

        final HistogramSample histogram = capture.histogramWithSamples(loopName);
        assertEquals(EventLoopMetrics.SOURCE, histogram.source);
        assertEquals(TASK_LATENCY_NAME, histogram.name);
        // labels() is the raw concatenated "key=value" String; no service property set here.
        assertEquals("loop=" + loopName, histogram.rawLabels);
        // The handler was busy for ~500 iterations; all land in the flushed windows.
        assertTrue(histogram.count >= 1, "expected at least one busy iteration, was " + histogram.count);
        assertTrue(histogram.count < 100_000_000L, "implausible sample count " + histogram.count);
        assertTrue(histogram.worst > 0.0, "worst latency should be positive, was " + histogram.worst);
        assertTrue(histogram.eventTime > 0, "eventTime not stamped");
        assertTrue(histogram.intervalNs > 0, "intervalNs not stamped");

        final GaugeSample busy = capture.firstGauge(loopName, BUSY_RATIO_NAME);
        assertEquals(EventLoopMetrics.SOURCE, busy.source);
        assertTrue(busy.value >= 0.0 && busy.value <= 1.0, "busy ratio out of range: " + busy.value);
        assertTrue(busy.eventTime > 0, "eventTime not stamped");
        assertTrue(busy.intervalNs > 0, "intervalNs not stamped");

        final GaugeSample idle = capture.firstGauge(loopName, IDLE_RATIO_NAME);
        assertTrue(idle.value >= 0.0 && idle.value <= 1.0, "idle ratio out of range: " + idle.value);

        final CounterSample iterations = capture.firstCounter(loopName);
        assertEquals(ITERATIONS_NAME, iterations.name);
        assertTrue(iterations.count >= 1, "expected iterations, was " + iterations.count);
        // The per-flush iteration count is the counter's window delta.
        assertTrue(iterations.delta >= 0, "delta must be non-negative, was " + iterations.delta);

        assertEquals(0, capture.otherKinds.get(), "only histogram, gauge and counter events expected");
    }

    @Test
    void noMetricEventsWhenNothingInstalledAtLoopConstruction() {
        expectException("Metrics.install() was called after");
        final CapturingMetricsOut capture = new CapturingMetricsOut();
        final String loopName = "metrics-disabled-loop";
        try (MediumEventLoop eventLoop = new MediumEventLoop(null, loopName, Pauser.balanced(), true, null)) {
            // Installing after construction must not enable this loop: the source is resolved
            // once, at construction, via Metrics.forSourceStatic (startup-only policy).
            Metrics.install(source -> capture);

            final AtomicInteger remaining = new AtomicInteger(100);
            eventLoop.addHandler(() -> remaining.getAndDecrement() > 0);
            eventLoop.start();
            Waiters.waitForCondition("handler ran", () -> remaining.get() <= 0, 5_000);
            Jvm.pause(100);
        }
        assertEquals(0, capture.total(), "no metric events expected from a loop built with no binding");
    }

    /**
     * Review #89 / #3: two loops, one binding - each loop owns an independent registry, so a
     * flush by loop A's thread emits only A's instruments, never B's. Every flush happens
     * synchronously on the owning loop's thread, so grouping captured events by emitting
     * thread proves independence: each emitting thread carries exactly one {@code loop}
     * label, and that thread is the loop's own.
     */
    @Test
    void twoLoopsFlushOnlyTheirOwnInstruments() {
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "25");
        final CapturingMetricsOut capture = new CapturingMetricsOut();
        Metrics.install(source -> capture);

        final String loopA = "metrics-independent-a";
        final String loopB = "metrics-independent-b";
        try (MediumEventLoop a = new MediumEventLoop(null, loopA, Pauser.balanced(), true, null);
             MediumEventLoop b = new MediumEventLoop(null, loopB, Pauser.balanced(), true, null)) {
            final AtomicInteger remainingA = new AtomicInteger(10_000);
            final AtomicInteger remainingB = new AtomicInteger(10_000);
            a.addHandler(() -> remainingA.getAndDecrement() > 0);
            b.addHandler(() -> remainingB.getAndDecrement() > 0);
            a.start();
            b.start();

            Waiters.waitForCondition("both loops flushed at least twice",
                    () -> capture.gaugeCount(loopA, BUSY_RATIO_NAME) >= 2
                            && capture.gaugeCount(loopB, BUSY_RATIO_NAME) >= 2,
                    5_000);
        }

        final Map<String, Set<String>> loopLabelsByThread = capture.loopLabelsByEmittingThread();
        final Set<String> allLoopLabels = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : loopLabelsByThread.entrySet()) {
            final String threadName = entry.getKey();
            final Set<String> loopLabels = entry.getValue();
            assertEquals(1, loopLabels.size(),
                    "a flush from thread '" + threadName + "' emitted instruments of several loops: " + loopLabels);
            final String loopLabel = loopLabels.iterator().next();
            assertTrue(threadName.contains(loopLabel),
                    "loop '" + loopLabel + "' instruments were flushed by a foreign thread '" + threadName + "'");
            allLoopLabels.add(loopLabel);
        }
        assertTrue(allLoopLabels.contains(loopA), "loop A never flushed");
        assertTrue(allLoopLabels.contains(loopB), "loop B never flushed");
    }

    /**
     * Review #89 / #90: closing the loop closes its registry, so a closed loop's series stop
     * being emitted - no stale series after close() returns.
     */
    @Test
    void closedLoopEmitsNoFurtherMetricEvents() {
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "25");
        final CapturingMetricsOut capture = new CapturingMetricsOut();
        Metrics.install(source -> capture);

        final String loopName = "metrics-closed-loop";
        final MediumEventLoop eventLoop = new MediumEventLoop(null, loopName, Pauser.balanced(), true, null);
        try {
            final AtomicInteger remaining = new AtomicInteger(1_000_000);
            eventLoop.addHandler(() -> remaining.getAndDecrement() > 0);
            eventLoop.start();
            Waiters.waitForCondition("loop flushed while running",
                    () -> capture.gaugeCount(loopName, BUSY_RATIO_NAME) >= 2, 5_000);
        } finally {
            eventLoop.close();
        }

        final int afterClose = capture.total();
        // Several flush intervals: a stale registry would keep emitting.
        Jvm.pause(200);
        assertEquals(afterClose, capture.total(), "a closed loop must not emit further metric events");
    }

    /**
     * Review #87: a zero or negative flush interval disables event-loop metrics with a single
     * warning per JVM - it must not busy-flush every iteration.
     */
    @Test
    void zeroOrNegativeFlushIntervalDisablesMetricsWithOneWarning() {
        expectException("event-loop metrics disabled");
        EventLoopMetrics.resetFlushIntervalWarningForTesting();

        final CapturingMetricsOut capture = new CapturingMetricsOut();
        Metrics.install(source -> capture);

        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "0");
        try (MediumEventLoop eventLoop = new MediumEventLoop(null, "metrics-interval-zero", Pauser.balanced(), true, null)) {
            final AtomicInteger remaining = new AtomicInteger(200);
            eventLoop.addHandler(() -> remaining.getAndDecrement() > 0);
            eventLoop.start();
            Waiters.waitForCondition("handler ran", () -> remaining.get() <= 0, 5_000);
            Jvm.pause(50);
        }

        // A second offending loop must not warn again (warn-once) and stays disabled too;
        // an unexpected second warning would fail ThreadsTestCommon's exception check.
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "-100");
        try (MediumEventLoop eventLoop = new MediumEventLoop(null, "metrics-interval-negative", Pauser.balanced(), true, null)) {
            eventLoop.start();
            Jvm.pause(50);
        }

        assertEquals(0, capture.total(), "a disabling interval must suppress all metric events");
    }

    /**
     * Review #88: when the system property {@code chronicle.metrics.service} is set, its
     * value is added as a {@code service} label on every event-loop instrument.
     */
    @Test
    void serviceLabelAppendedWhenPropertySet() {
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "25");
        System.setProperty(EventLoopMetrics.SERVICE_LABEL_PROPERTY, "order-gateway");
        final CapturingMetricsOut capture = new CapturingMetricsOut();
        Metrics.install(source -> capture);

        final String loopName = "metrics-service-label-loop";
        try (MediumEventLoop eventLoop = new MediumEventLoop(null, loopName, Pauser.balanced(), true, null)) {
            final AtomicInteger remaining = new AtomicInteger(500);
            eventLoop.addHandler(() -> remaining.getAndDecrement() > 0);
            eventLoop.start();
            Waiters.waitForCondition("gauge flushed",
                    () -> capture.firstGauge(loopName, BUSY_RATIO_NAME) != null, 5_000);
        }

        final GaugeSample gauge = capture.firstGauge(loopName, BUSY_RATIO_NAME);
        assertEquals("loop=" + loopName + ";service=order-gateway", gauge.rawLabels);
        final HistogramSample histogram = capture.firstHistogram(loopName);
        assertNotNull(histogram);
        assertEquals("loop=" + loopName + ";service=order-gateway", histogram.rawLabels);
    }

    @Test
    void nullLoopNameIsExportedAsUnnamed() {
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "1");
        final CapturingMetricsOut capture = new CapturingMetricsOut();
        Metrics.install(source -> capture);

        final EventLoopMetrics metrics = EventLoopMetrics.createIfEnabled(null);
        assertNotNull(metrics, "metrics should be enabled with a binding installed");
        try {
            metrics.loopStarted(System.nanoTime());
            metrics.onIteration(System.nanoTime() - 1_000, true);
            metrics.loopFinished();
        } finally {
            metrics.close();
        }

        assertNotNull(capture.firstCounter("unnamed"));
        assertNotNull(capture.firstGauge("unnamed", BUSY_RATIO_NAME));
    }

    @Test
    void sinkFailureDoesNotEscapeOrReplayTheDroppedWindow() {
        expectException("Event-loop metrics sink threw");
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "1");
        final ThrowingOnceMetricsOut sink = new ThrowingOnceMetricsOut();
        Metrics.install(source -> EventLoopMetrics.SOURCE.equals(source) ? sink : null);

        final EventLoopMetrics metrics = EventLoopMetrics.createIfEnabled("metrics-throwing-loop");
        assertNotNull(metrics, "metrics should be enabled with a binding installed");
        try {
            metrics.loopStarted(System.nanoTime());
            metrics.onIteration(System.nanoTime() - 1_000, true);
            metrics.loopFinished();
            assertEquals(0, sink.capture.total(), "the failed window should be dropped");

            metrics.loopStarted(System.nanoTime());
            metrics.onIteration(System.nanoTime() - 1_000, true);
            metrics.loopFinished();

            final CounterSample iterations = sink.capture.firstCounter("metrics-throwing-loop");
            assertNotNull(iterations);
            assertEquals(1, iterations.delta, "the failed window must not be replayed");
        } finally {
            metrics.close();
        }
    }

    /**
     * Review #28: allocation gate on the per-iteration recording path. Drives
     * {@link EventLoopMetrics#onIteration(long, boolean)} directly (the exact code the loop
     * thread runs per iteration) with a cadence long enough that no flush occurs, and asserts
     * the thread allocates nothing.
     */
    @Test
    @SuppressWarnings("deprecation") // Thread.getId(): the Java 8-compatible way to get the thread id
    void perIterationRecordingPathIsAllocationFree() {
        final java.lang.management.ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        assumeTrue(mxBean instanceof com.sun.management.ThreadMXBean,
                "thread allocation accounting not available");
        final com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) mxBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "thread allocation accounting not supported");
        if (!bean.isThreadAllocatedMemoryEnabled())
            bean.setThreadAllocatedMemoryEnabled(true);

        // No flush during the measured window; the gate covers the recording path only.
        System.setProperty(EventLoopMetrics.FLUSH_INTERVAL_MS_PROPERTY, "3600000");
        Metrics.install(source -> NoOpMetricsOut.INSTANCE);
        final EventLoopMetrics metrics = EventLoopMetrics.createIfEnabled("metrics-alloc-gate-loop");
        assertNotNull(metrics, "metrics should be enabled with a binding installed");
        try {
            metrics.loopStarted(System.nanoTime());
            // Warm up: JIT the path and populate the histogram's internal state.
            recordIterations(metrics, 20_000);

            final long threadId = Thread.currentThread().getId();
            long allocated = -1;
            for (int attempt = 0; attempt < 5; attempt++) {
                final long before = bean.getThreadAllocatedBytes(threadId);
                recordIterations(metrics, 100_000);
                allocated = bean.getThreadAllocatedBytes(threadId) - before;
                if (allocated == 0)
                    return;
            }
            fail("per-iteration recording path allocated " + allocated + " bytes over 100000 iterations");
        } finally {
            metrics.close();
        }
    }

    private static void recordIterations(final EventLoopMetrics metrics, final int count) {
        for (int i = 0; i < count; i++)
            metrics.onIteration(System.nanoTime() - 100, (i & 1) == 0);
    }

    /**
     * A non-{@code IgnoresEverything} sink that discards everything; never invoked in the
     * allocation gate (no flush happens) but required so metrics resolve as enabled.
     */
    enum NoOpMetricsOut implements MetricsOut {
        INSTANCE;

        @Override
        public void counterMetric(CounterMetric metric) {
        }

        @Override
        public void gaugeMetric(GaugeMetric metric) {
        }

        @Override
        public void histogramMetric(HistogramMetric metric) {
        }

        @Override
        public void rateMetric(RateMetric metric) {
        }

        @Override
        public void pointEvent(PointEvent metric) {
        }
    }

    static final class ThrowingOnceMetricsOut implements MetricsOut {
        final CapturingMetricsOut capture = new CapturingMetricsOut();
        private boolean throwNext = true;

        @Override
        public void counterMetric(CounterMetric metric) {
            maybeThrow();
            capture.counterMetric(metric);
        }

        @Override
        public void gaugeMetric(GaugeMetric metric) {
            maybeThrow();
            capture.gaugeMetric(metric);
        }

        @Override
        public void histogramMetric(HistogramMetric metric) {
            maybeThrow();
            capture.histogramMetric(metric);
        }

        @Override
        public void rateMetric(RateMetric metric) {
            maybeThrow();
            capture.rateMetric(metric);
        }

        @Override
        public void pointEvent(PointEvent metric) {
            maybeThrow();
            capture.pointEvent(metric);
        }

        private void maybeThrow() {
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("simulated event-loop sink failure");
            }
        }
    }

    /**
     * Captures metric events by copying the fields out immediately - the emitting side
     * reuses one DTO instance per instrument, so references must not be retained. Also
     * records the emitting thread's name: owner-flush means the emitting thread is the
     * owning loop's thread.
     */
    static final class CapturingMetricsOut implements MetricsOut {
        final List<HistogramSample> histograms = new ArrayList<>();
        final List<GaugeSample> gauges = new ArrayList<>();
        final List<CounterSample> counters = new ArrayList<>();
        final AtomicInteger otherKinds = new AtomicInteger();

        @Override
        public synchronized void histogramMetric(HistogramMetric metric) {
            histograms.add(new HistogramSample(metric));
        }

        @Override
        public synchronized void gaugeMetric(GaugeMetric metric) {
            gauges.add(new GaugeSample(metric));
        }

        @Override
        public synchronized void counterMetric(CounterMetric metric) {
            counters.add(new CounterSample(metric));
        }

        @Override
        public void rateMetric(RateMetric metric) {
            otherKinds.incrementAndGet();
        }

        @Override
        public void pointEvent(PointEvent metric) {
            otherKinds.incrementAndGet();
        }

        synchronized HistogramSample histogramWithSamples(String loopName) {
            for (int i = 0; i < histograms.size(); i++) {
                HistogramSample sample = histograms.get(i);
                if (loopName.equals(sample.loop) && sample.count > 0)
                    return sample;
            }
            return null;
        }

        synchronized HistogramSample firstHistogram(String loopName) {
            for (int i = 0; i < histograms.size(); i++) {
                HistogramSample sample = histograms.get(i);
                if (loopName.equals(sample.loop))
                    return sample;
            }
            return null;
        }

        synchronized GaugeSample firstGauge(String loopName, String name) {
            for (int i = 0; i < gauges.size(); i++) {
                GaugeSample sample = gauges.get(i);
                if (loopName.equals(sample.loop) && name.equals(sample.name))
                    return sample;
            }
            return null;
        }

        synchronized int gaugeCount(String loopName, String name) {
            int count = 0;
            for (int i = 0; i < gauges.size(); i++) {
                GaugeSample sample = gauges.get(i);
                if (loopName.equals(sample.loop) && name.equals(sample.name))
                    count++;
            }
            return count;
        }

        synchronized CounterSample firstCounter(String loopName) {
            for (int i = 0; i < counters.size(); i++) {
                CounterSample sample = counters.get(i);
                if (loopName.equals(sample.loop))
                    return sample;
            }
            return null;
        }

        synchronized Map<String, Set<String>> loopLabelsByEmittingThread() {
            final Map<String, Set<String>> byThread = new HashMap<>();
            for (int i = 0; i < histograms.size(); i++)
                addLoopLabel(byThread, histograms.get(i).emittingThread, histograms.get(i).loop);
            for (int i = 0; i < gauges.size(); i++)
                addLoopLabel(byThread, gauges.get(i).emittingThread, gauges.get(i).loop);
            for (int i = 0; i < counters.size(); i++)
                addLoopLabel(byThread, counters.get(i).emittingThread, counters.get(i).loop);
            return byThread;
        }

        private static void addLoopLabel(Map<String, Set<String>> byThread, String thread, String loop) {
            byThread.computeIfAbsent(thread, t -> new HashSet<>()).add(loop);
        }

        synchronized int total() {
            return histograms.size() + gauges.size() + counters.size() + otherKinds.get();
        }
    }

    static final class HistogramSample {
        final String source;
        final String name;
        final String loop;
        final String rawLabels;
        final long count;
        final double worst;
        final long eventTime;
        final long intervalNs;
        final String emittingThread;

        HistogramSample(HistogramMetric metric) {
            this.source = metric.source();
            this.name = metric.name();
            this.loop = labelValue(metric);
            this.rawLabels = metric.labels();
            this.count = metric.histogram().count();
            this.worst = metric.histogram().worst();
            this.eventTime = metric.eventTime();
            this.intervalNs = metric.intervalNs();
            this.emittingThread = Thread.currentThread().getName();
        }
    }

    static final class GaugeSample {
        final String source;
        final String name;
        final String loop;
        final String rawLabels;
        final double value;
        final long eventTime;
        final long intervalNs;
        final String emittingThread;

        GaugeSample(GaugeMetric metric) {
            this.source = metric.source();
            this.name = metric.name();
            this.loop = labelValue(metric);
            this.rawLabels = metric.labels();
            this.value = metric.value();
            this.eventTime = metric.eventTime();
            this.intervalNs = metric.intervalNs();
            this.emittingThread = Thread.currentThread().getName();
        }
    }

    static final class CounterSample {
        final String source;
        final String name;
        final String loop;
        final String rawLabels;
        final long count;
        final long delta;
        final String emittingThread;

        CounterSample(CounterMetric metric) {
            this.source = metric.source();
            this.name = metric.name();
            this.loop = labelValue(metric);
            this.rawLabels = metric.labels();
            this.count = metric.count();
            this.delta = metric.delta();
            this.emittingThread = Thread.currentThread().getName();
        }
    }

    /**
     * Extracts the {@code loop} label. {@code labels()} is the raw concatenated
     * {@code "key=value;key2=value2"} String; {@code decodeLabels()} (allocating, fine in
     * test capture code) provides the Map view.
     */
    static String labelValue(Metric<?> metric) {
        final Map<String, String> labels = metric.decodeLabels();
        return labels == null ? null : labels.get("loop");
    }
}
