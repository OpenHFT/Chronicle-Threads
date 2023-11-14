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
import net.openhft.chronicle.core.time.SystemTimeProvider;
import net.openhft.chronicle.core.time.TimeProvider;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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
    public static final int TIME_TAKEN_WARN_THRESHOLD_US = Jvm.getInteger("chronicle.disk.monitor.warn.threshold.us", 250);
    private final NotifyDiskLow notifyDiskLow;
    final Map<String, FileStore> fileStoreCacheMap = new ConcurrentHashMap<>();
    final Map<FileStore, DiskAttributes> diskAttributesMap = new ConcurrentHashMap<>();
    final ScheduledExecutorService executor;
    private int thresholdPercentage = Jvm.getInteger("chronicle.disk.monitor.threshold.percent", 0);
    private TimeProvider timeProvider = SystemTimeProvider.INSTANCE;

    DiskSpaceMonitor() {
        if (!Jvm.getBoolean("chronicle.disk.monitor.disable")) {
            executor = Threads.acquireScheduledExecutorService(DISK_SPACE_CHECKER_NAME, true);
            executor.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
        } else {
            executor = null;
        }

        final ServiceLoader<NotifyDiskLow> services = ServiceLoader.load(NotifyDiskLow.class);
        if (services.iterator().hasNext()) {
            final List<NotifyDiskLow> warners = new ArrayList<>();
            services.iterator().forEachRemaining(warners::add);
            this.notifyDiskLow = new NotifyDiskLowIterator(warners);
        } else {
            this.notifyDiskLow = new NotifyDiskLowLogWarn();
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
        long start = timeProvider.currentTimeNanos();

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

        final long tookUs = (timeProvider.currentTimeNanos() - start) / 1_000;
        if (tookUs > TIME_TAKEN_WARN_THRESHOLD_US)
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

    @VisibleForTesting
    protected void setTimeProvider(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    @Override
    public void close() {
        if (executor != null)
            Threads.shutdown(executor);
    }

    final class DiskAttributes {

        private final FileStore fileStore;

        long timeNextCheckedMS;
        long totalSpace;

        DiskAttributes(FileStore fileStore) {
            this.fileStore = fileStore;
        }

        void run() throws IOException {
            long now = timeProvider.currentTimeMillis();
            if (timeNextCheckedMS > now)
                return;

            long start = System.nanoTime();
            if (totalSpace <= 0)
                totalSpace = fileStore.getTotalSpace();

            long unallocatedBytes = fileStore.getUnallocatedSpace();
            if (unallocatedBytes < (200 << 20)) {
                // if less than 200 Megabytes
                notifyDiskLow.panic(fileStore);

            } else if (unallocatedBytes < totalSpace * DiskSpaceMonitor.INSTANCE.thresholdPercentage / 100) {
                final double diskSpaceFull = ((long) (1000d * (totalSpace - unallocatedBytes) / totalSpace + 0.999)) / 10.0;
                notifyDiskLow.warning(diskSpaceFull, fileStore);

            } else {
                // wait 1 ms per MB or approx 1 sec per GB free.
                timeNextCheckedMS = now + (unallocatedBytes >> 20);
            }
            long time = System.nanoTime() - start;
            if (time > 1_000_000)
                Jvm.perf().on(getClass(), "Took " + time / 10_000 / 100.0 + " ms to check the disk space of " + fileStore);
        }
    }

    private static class NotifyDiskLowIterator implements NotifyDiskLow {
        private final List<NotifyDiskLow> list;

        public NotifyDiskLowIterator(List<NotifyDiskLow> list) {
            this.list = list;
        }

        @Override
        public void panic(FileStore fileStore) {
            for (NotifyDiskLow mfy : list)
                mfy.panic(fileStore);
        }

        @Override
        public void warning(double diskSpaceFullPercent, FileStore fileStore) {
            for (NotifyDiskLow mfy : list)
                mfy.warning(diskSpaceFullPercent, fileStore);
        }
    }
}
