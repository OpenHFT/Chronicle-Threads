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

public class DiskSpaceMonitorTest extends ThreadsTestCommon {

    @BeforeEach
    public void beforeEach(){
        clearState();
    }

    @AfterEach
    public void afterEach(){
        clearState();
    }

    private void clearState() {
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(0);
        DiskSpaceMonitor.INSTANCE.clear();
    }

    @Test
    public void pollDiskSpace() {
        // todo investigate why this fails on arm
        assumeTrue(!Jvm.isArm());
        Map<ExceptionKey, Integer> map = Jvm.recordExceptions();
        assertEquals(0, DiskSpaceMonitor.INSTANCE.getThresholdPercentage());
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(100);
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
        assertEquals(5, count, 1);
    }

    /**
     * This test was created to verify that the core monitoring loop actually runs more than once. It used to run once
     * and then never again. This test explicitly changes the threshold after the first run has happened to ensure that
     * a failure occurs on a subsequent run.
     */
    @Test
    public void ensureThatDiskSpaceMonitorRunsForMoreThanOneIteration() throws InterruptedException {
        SetTimeProvider timeProvider = new SetTimeProvider();
        expectException("warning: the JVM may crash if it undertakes an operation with a memory-mapped file and the disk is out of space");
        DiskSpaceMonitor.INSTANCE.pollDiskSpace(new File("."));
        timeProvider.advanceMillis(1200);
        DiskSpaceMonitor.INSTANCE.setThresholdPercentage(100);
        timeProvider.advanceMillis(Duration.ofHours(24).toMillis());
        Thread.sleep(1000);
    }

}