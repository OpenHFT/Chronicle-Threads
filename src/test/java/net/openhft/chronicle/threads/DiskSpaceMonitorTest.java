/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
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
    }

    private void clearState() {
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(0);
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
        System.setProperty("chronicle.disk.monitor.threshold.percent", "0");
        assertEquals(0, DiskSpaceMonitor.INSTANCE.getThresholdPercentage(), "default disk monitor threshold");
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(100);
        final Map<ExceptionKey, Integer> map = Jvm.recordExceptions();
        for (int i = 0; i < 51; i++) {
            DiskSpaceMonitor.INSTANCE.pollDiskSpace(new File("."));
            Jvm.pause(100);
        }
        DiskSpaceMonitor.INSTANCE.clear();
        map.entrySet().forEach(System.out::println);
        long count = map.entrySet()
                .stream()
                .filter(e -> e.getKey().clazz() == DiskSpaceMonitor.class)
                .mapToInt(Map.Entry::getValue)
                .sum();
        Jvm.resetExceptionHandlers();
        // look for 5 disk space checks and some debug messages about slow disk checks.
        assertEquals(5.5, count, 1.5, "disk space warnings count within tolerance");
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
        assertEquals(100, DiskSpaceMonitor.INSTANCE.getThresholdPercentage(), "disk monitor threshold updated");
        timeProvider.advanceMillis(Duration.ofHours(24).toMillis());
        Thread.sleep(1000);
    }
}
