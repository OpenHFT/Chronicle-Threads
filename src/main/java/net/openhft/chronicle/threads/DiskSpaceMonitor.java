/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background thread to monitor disk space free.
 */
public enum DiskSpaceMonitor implements Runnable, Closeable {
    INSTANCE;

    public static final String DISK_SPACE_CHECKER_NAME = "disk~space~checker";
    static final boolean WARN_DELETED = Jvm.getBoolean("disk.monitor.deleted.warning");
    private static final boolean DISABLED = Jvm.getBoolean("chronicle.disk.monitor.disable");
    final Map<String, FileStore> fileStoreCacheMap = new ConcurrentHashMap<>();
    final Map<FileStore, DiskAttributes> diskAttributesMap = new ConcurrentHashMap<>();
    final ScheduledExecutorService executor;
    private int thresholdPercentage = Integer.getInteger("chronicle.disk.monitor.threshold.percent", 0);

    DiskSpaceMonitor() {
        if (!Jvm.getBoolean("chronicle.disk.monitor.disable")) {
            executor = Threads.acquireScheduledExecutorService(DISK_SPACE_CHECKER_NAME, true);
            executor.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
        } else {
            executor = null;
        }
    }

    // used for testing purposes
    public void clear() {
        fileStoreCacheMap.clear();
        diskAttributesMap.clear();
    }

    public void pollDiskSpace(File file) {
        if (DISABLED)
            return;
        long start = System.nanoTime();

        final String absolutePath = file.getAbsolutePath();
        FileStore fs = fileStoreCacheMap.get(absolutePath);
        if (fs == null) {
            if (file.exists()) {

                Path path = Paths.get(absolutePath);
                try {
                    fs = Files.getFileStore(path);
                    fileStoreCacheMap.put(absolutePath, fs);
                } catch (IOException e) {
                    Jvm.warn().on(getClass(), "Error trying to obtain the FileStore for " + path, e);
                    return;
                }
            } else {
                // nothing to monitor if it doesn't exist.
                return;
            }
        }
        DiskAttributes da = diskAttributesMap.computeIfAbsent(fs, DiskAttributes::new);
        da.polled = true;

        final long tookUs = (System.nanoTime() - start) / 1_000;
        if (tookUs > 250)
            Jvm.perf().on(getClass(), "Took " + tookUs / 1000.0 + " ms to pollDiskSpace for " + file.getAbsolutePath());
    }

    @Override
    public void run() {
        for (Iterator<DiskAttributes> iterator = diskAttributesMap.values().iterator(); iterator.hasNext(); ) {
            DiskAttributes da = iterator.next();
            try {
                da.run();
            } catch (IOException e) {
                if (WARN_DELETED)
                    Jvm.warn().on(getClass(), "Unable to get disk space for " + da.fileStore, e);
                iterator.remove();
            }
        }
    }

    public int getThresholdPercentage() {
        return thresholdPercentage;
    }

    public void setThresholdPercentage(int thresholdPercentage) {
        this.thresholdPercentage = thresholdPercentage;
    }

    @Override
    public void close() {
        if (executor != null)
            Threads.shutdown(executor);
    }

    static final class DiskAttributes {

        private final FileStore fileStore;

        volatile boolean polled;
        long timeNextCheckedMS;
        long totalSpace;

        DiskAttributes(FileStore fileStore) {
            this.fileStore = fileStore;
        }

        void run() throws IOException {
            long now = System.currentTimeMillis();
            if (timeNextCheckedMS > now || !polled)
                return;

            polled = false;
            long start = System.nanoTime();
            if (totalSpace <= 0)
                totalSpace = fileStore.getTotalSpace();

            long unallocatedBytes = fileStore.getUnallocatedSpace();
            if (unallocatedBytes < (200 << 20)) {
                // if less than 200 Megabytes
                Jvm.warn().on(getClass(), "your disk " + fileStore + " is almost full, " +
                        "warning: chronicle-queue may crash if it runs out of space.");

            } else if (unallocatedBytes < totalSpace * DiskSpaceMonitor.INSTANCE.thresholdPercentage / 100) {
                final double diskSpaceFull = ((long) (1000d * (totalSpace - unallocatedBytes) / totalSpace + 0.999)) / 10.0;
                Jvm.warn().on(getClass(), "your disk " + fileStore
                        + " is " + diskSpaceFull + "% full, " +
                        "warning: chronicle-queue may crash if it runs out of space.");

            } else {
                // wait 1 ms per MB or approx 1 sec per GB free.
                timeNextCheckedMS = now + (unallocatedBytes >> 20);
            }
            long time = System.nanoTime() - start;
            if (time > 1_000_000)
                Jvm.perf().on(getClass(), "Took " + time / 10_000 / 100.0 + " ms to check the disk space of " + fileStore);
        }
    }
}
