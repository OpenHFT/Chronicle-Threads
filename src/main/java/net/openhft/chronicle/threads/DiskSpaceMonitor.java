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
 * Singleton implementation of a disk space monitor that uses a background thread to track free disk space
 * on specified file stores. Provides warnings and panic alerts when disk space runs low, and can be configured
 * with various thresholds and behaviors via system properties.
 */
public enum DiskSpaceMonitor implements Runnable, Closeable {
    INSTANCE;

    public static final String DISK_SPACE_CHECKER_NAME = "disk~space~checker";
    static final boolean WARN_DELETED = Jvm.getBoolean("disk.monitor.deleted.warning");
    private static final boolean DISABLED = Jvm.getBoolean("chronicle.disk.monitor.disable");
    public static final int TIME_TAKEN_WARN_THRESHOLD_US = Jvm.getInteger("chronicle.disk.monitor.warn.threshold.us", 250);

    private final NotifyDiskLow notifyDiskLow;  // Handler for low disk space notifications
    final Map<String, FileStore> fileStoreCacheMap = new ConcurrentHashMap<>();  // Caches FileStores by path
    final Map<FileStore, DiskAttributes> diskAttributesMap = new ConcurrentHashMap<>();  // Maps FileStores to their attributes
    final ScheduledExecutorService executor;
    private int thresholdPercentage = Jvm.getInteger("chronicle.disk.monitor.threshold.percent", 5);  // Disk space threshold percentage
    private TimeProvider timeProvider = SystemTimeProvider.INSTANCE;  // Time provider for tracking time in tests and operations

    /**
     * Initializes the DiskSpaceMonitor, setting up the scheduled task for monitoring
     * and loading any NotifyDiskLow services if present.
     */
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

    /**
     * Clears cached data. Used for testing purposes to reset monitor state.
     */
    public void clear() {
        fileStoreCacheMap.clear();
        diskAttributesMap.clear();
    }

    /**
     * Polls disk space for the given file, tracking the time taken to perform the check
     * and logging performance if the operation exceeds a warning threshold.
     *
     * @param file the {@link File} whose associated disk space is being monitored
     */
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

    /**
     * The main run method for the scheduled executor, which iterates over each FileStore
     * and updates their disk space attributes.
     */
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

    /**
     * Retrieves the current disk space threshold percentage.
     *
     * @return the threshold percentage for triggering low disk space warnings
     */
    public int getThresholdPercentage() {
        return thresholdPercentage;
    }

    /**
     * Sets the disk space threshold percentage for warnings.
     *
     * @param thresholdPercentage the new threshold percentage
     */
    public void setThresholdPercentage(int thresholdPercentage) {
        this.thresholdPercentage = thresholdPercentage;
    }

    /**
     * Sets the time provider, mainly for testing purposes.
     *
     * @param timeProvider the new {@link TimeProvider}
     */
    @VisibleForTesting
    protected void setTimeProvider(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    /**
     * Shuts down the executor service and releases any resources held by this monitor.
     */
    @Override
    public void close() {
        if (executor != null)
            Threads.shutdown(executor);
    }

    /**
     * Represents disk space attributes and performs monitoring actions.
     */
    final class DiskAttributes {

        private final FileStore fileStore;
        long timeNextCheckedMS;  // The next time this FileStore should be checked, in milliseconds
        long totalSpace;  // Total space of the FileStore, set on first run

        DiskAttributes(FileStore fileStore) {
            this.fileStore = fileStore;
        }

        /**
         * Checks the disk space of the associated FileStore and issues warnings if thresholds
         * are met. Updates the next check time based on the available free space.
         *
         * @throws IOException if an error occurs while accessing the FileStore
         */
        void run() throws IOException {
            long now = timeProvider.currentTimeMillis();
            if (timeNextCheckedMS > now)
                return;

            long start = System.nanoTime();
            if (totalSpace <= 0)
                totalSpace = fileStore.getTotalSpace();

            long unallocatedBytes = fileStore.getUnallocatedSpace();
            if (unallocatedBytes < (200 << 20)) {
                // Less than 200 MB free space
                notifyDiskLow.panic(fileStore);

            } else if (unallocatedBytes < totalSpace * DiskSpaceMonitor.INSTANCE.thresholdPercentage / 100) {
                final double diskSpaceFull = ((long) (1000d * (totalSpace - unallocatedBytes) / totalSpace + 0.999)) / 10.0;
                notifyDiskLow.warning(diskSpaceFull, fileStore);

            } else {
                // Wait 1 ms per MB or approx 1 sec per GB free
                timeNextCheckedMS = now + (unallocatedBytes >> 20);
            }
            long time = System.nanoTime() - start;
            if (time > 1_000_000)
                Jvm.perf().on(getClass(), "Took " + time / 10_000 / 100.0 + " ms to check the disk space of " + fileStore);
        }
    }

    /**
     * Handles low disk space notifications by iterating through a list of notification handlers.
     */
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
