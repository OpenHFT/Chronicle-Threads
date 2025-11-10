//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//

package net.openhft.chronicle.threads;

import java.nio.file.FileStore;

/**
 * Receives notifications from the disk space monitor.
 *
 * <p>The {@link #panic(FileStore)} method is called when a file store
 * is critically short of space. Implementations should act immediately
 * as memory-mapped writes may fail.</p>
 *
 * <p>The {@link #warning(double, FileStore)} method signals that a disk
 * is nearing its limit. The percentage parameter denotes how full the disk
 * currently is.</p>
 */
public interface NotifyDiskLow {
    void panic(FileStore fileStore);

    void warning(double diskSpaceFullPercent, FileStore fileStore);
}
