/*
 * Copyright 2016-2020 chronicle.software
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

import java.nio.file.FileStore;

/**
 * The {@code NotifyDiskLowLogWarn} class implements the {@link NotifyDiskLow} interface,
 * providing logging-based notifications when disk space is low. Warnings and panic alerts
 * are logged to inform users of potential issues with disk space, especially in contexts
 * where memory-mapped files are involved.
 */
public class NotifyDiskLowLogWarn implements NotifyDiskLow {

    /**
     * Logs a critical warning message indicating that the specified {@link FileStore} is nearly full.
     * This is intended to alert users that actions involving memory-mapped files may cause the JVM to crash.
     *
     * @param fileStore the {@link FileStore} where disk space is critically low
     */
    @Override
    public void panic(FileStore fileStore) {
        Jvm.error().on(DiskSpaceMonitor.class, "your disk " + fileStore + " is almost full, " +
                "warning: the JVM may crash if it undertakes an operation with a memory-mapped file.");
    }

    /**
     * Logs a warning message indicating that the specified {@link FileStore} has reached a specified
     * threshold of disk space usage. This serves as a preventive alert to avoid reaching critical levels.
     *
     * @param diskSpaceFullPercent the percentage of disk space currently in use
     * @param fileStore            the {@link FileStore} where disk space is low
     */
    @Override
    public void warning(double diskSpaceFullPercent, FileStore fileStore) {
        Jvm.warn().on(DiskSpaceMonitor.class, "your disk " + fileStore
                + " is " + diskSpaceFullPercent + "% full, " +
                "warning: the JVM may crash if it undertakes an operation with a memory-mapped file and the disk is out of space.");
    }
}
