/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
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
 * Monitors free space on the disks used by this JVM.
 *
 * <p>Paths are registered via {@link #pollDiskSpace(File)}. The first call
 * obtains the {@link FileStore} for the supplied file and adds it to the
 * internal maps. Each subsequent call merely updates the cached entry. This
 * method is typically invoked when opening a queue or a memory-mapped file. A
 * scheduled executor named {@value #DISK_SPACE_CHECKER_NAME} then runs
 * the monitor once a second.</p>
 *
 * <p>The monitor may be disabled with the system property
 * {@code chronicle.disk.monitor.disable}. The threshold that triggers a
 * warning is controlled by {@code chronicle.disk.monitor.threshold.percent}.</p>
 *
 * <p>When the available space falls below these limits the monitor invokes a
 * {@link NotifyDiskLow} service. Implementations are discovered with
 * {@link java.util.ServiceLoader} and the default simply logs a warning.</p>
 *
 * <p>The {@link #run()} loop iterates over the tracked {@link DiskAttributes}
 * entries. Each record stores a {@link FileStore}, the time for the next check
 * and the total size. When the free space is less than two hundred megabytes a
 * panic notification is sent. Otherwise the next check is delayed based on the
 * amount of free space.</p>
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
    private int thresholdPercentage = Jvm.getInteger("chronicle.disk.monitor.threshold.percent", 5);
    private TimeProvider timeProvider = SystemTimeProvider.INSTANCE;

    DiskSpaceMonitor() {
        final ServiceLoader<NotifyDiskLow> services = ServiceLoader.load(NotifyDiskLow.class);
        if (services.iterator().hasNext()) {
            final List<NotifyDiskLow> warners = new ArrayList<>();
            services.iterator().forEachRemaining(warners::add);
            this.notifyDiskLow = new NotifyDiskLowIterator(warners);
        } else {
            this.notifyDiskLow = new NotifyDiskLowLogWarn();
        }
        boolean diabled = Jvm.getBoolean("chronicle.disk.monitor.disable");
        if (!diabled) {
            this.run(); // run once to initialise
            executor = Threads.acquireScheduledExecutorService(DISK_SPACE_CHECKER_NAME, true);
            long period = Jvm.getLong("chronicle.disk.monitor.period", 10L);
            executor.scheduleAtFixedRate(this, period, period, TimeUnit.SECONDS);
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
