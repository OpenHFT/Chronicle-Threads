/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.nio.file.FileStore;

/**
 * Logs to the configured {@link Jvm} logger when disk space is low.
 * The {@link #panic(FileStore)} method emits an error level message
 * and {@link #warning(double, FileStore)} emits a warning.
 */
public class NotifyDiskLowLogWarn implements NotifyDiskLow {
    /**
     * Creates a logger-backed low-disk notifier.
     */
    public NotifyDiskLowLogWarn() {
    }

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
