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

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.nio.file.FileStore;

/**
 * Logs to the configured {@link Jvm} logger when disk space is low.
 * The {@link #panic(FileStore)} method emits an error level message
 * and {@link #warning(double, FileStore)} emits a warning.
 */
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
