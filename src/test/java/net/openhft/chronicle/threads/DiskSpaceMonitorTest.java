/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.LogLevel;
import net.openhft.chronicle.core.time.SetTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiskSpaceMonitorTest extends ThreadsTestCommon {

    @BeforeEach
    void beforeEach(){
        clearState();
    }

    @AfterEach
    void afterEach(){
        clearState();
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(5);
    }

    private void clearState() {
        DiskSpaceMonitor.INSTANCE.clear();
    }

    /**
     * Exercises disk monitoring when the threshold is raised from zero to 100 per cent.
     * Exceptions are recorded and disk space is polled repeatedly to verify that
     * roughly five warnings are reported. The test is skipped on Arm hardware.
     */
    @Test
    void pollDiskSpace() {
        // todo investigate why this fails on arm
        assumeTrue(!Jvm.isArm());
        assertEquals(5, DiskSpaceMonitor.INSTANCE.getThresholdPercentage());
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(100);
        final Map<ExceptionKey, Integer> map = Jvm.recordExceptions();
        //! Slow disk probes emit PERF diagnostics independently of the scheduled low-space warning.
        //! Keep this control deterministic under both idle and busy hosts: pollDiskSpace must still
        //! require the original warning count when extra probe diagnostics are recorded.
        for (int i = 0; i < 8; i++)
            Jvm.perf().on(DiskSpaceMonitor.class, "Controlled slow disk probe " + i);
        for (int i = 0; i < 51; i++) {
            DiskSpaceMonitor.INSTANCE.pollDiskSpace(new File("."));
            Jvm.pause(100);
        }
        DiskSpaceMonitor.INSTANCE.clear();
        map.entrySet().forEach(System.out::println);
        long count = map.entrySet()
                .stream()
                .filter(e -> e.getKey().clazz() == DiskSpaceMonitor.class)
                .filter(e -> e.getKey().level() == LogLevel.WARN && e.getKey().message().startsWith("your disk "))
                .mapToInt(Map.Entry::getValue)
                .sum();
        Jvm.resetExceptionHandlers();
        System.out.println("Low disk-space warnings: " + count);
        // Require the scheduled warnings; probe performance messages are not additional checks.
        assertEquals(5.5, count, 1.5);
    }

    /**
     * This test was created to verify that the core monitoring loop actually runs more than once. It used to run once
     * and then never again. This test explicitly changes the threshold after the first run has happened to ensure that
     * a failure occurs on a subsequent run.
     */
    @Test
    void ensureThatDiskSpaceMonitorRunsForMoreThanOneIteration() throws InterruptedException {
        SetTimeProvider timeProvider = new SetTimeProvider();
        ignoreException("warning: the JVM may crash if it undertakes an operation with a memory-mapped file and the disk is out of space");
        DiskSpaceMonitor.INSTANCE.pollDiskSpace(new File("."));
        timeProvider.advanceMillis(1200);
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(100);
        timeProvider.advanceMillis(Duration.ofHours(24).toMillis());
        Thread.sleep(1000);
    }
}
