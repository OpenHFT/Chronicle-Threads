package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.nio.file.FileStore;

public class NotifyDiskLowLogWarn implements NotifyDiskLow {
    @Override
    public void panic(FileStore fileStore) {
        Jvm.error().on(DiskSpaceMonitor.class, "your disk " + fileStore + " is almost full, " +
                "warning: the JVM may crash if it undertakes an operation with a memory-mapped file.");
    }

    @Override
    public void warning(double diskSpaceFullPercent, FileStore fileStore) {
        Jvm.warn().on(DiskSpaceMonitor.class, "your disk " + fileStore
                + " is " + diskSpaceFullPercent + "% full, " +
                "warning: the JVM may crash if it undertakes an operation with a memory-mapped file and the disk is out of space.");
    }
}
