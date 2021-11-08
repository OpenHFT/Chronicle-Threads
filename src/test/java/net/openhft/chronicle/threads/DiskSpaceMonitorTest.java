package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.LogLevel;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class DiskSpaceMonitorTest extends ThreadsTestCommon {

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
                .filter(e -> e.getKey().level == LogLevel.WARN)
                .mapToInt(Map.Entry::getValue)
                .sum();
        Jvm.resetExceptionHandlers();
        // look for 5 disk space checks and some debug messages about slow disk checks.
        assertEquals(5, count, 1);
    }
}