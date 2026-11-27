/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import static net.openhft.chronicle.threads.DiskSpaceHealth.*;

import net.openhft.chronicle.core.Jvm;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class DiskSpaceHealthRegistry {
    interface Resolver {
        FileStore store(Path path) throws IOException;
    }

    private static final class Sample {
        final Reading value, expired;
        final long expiry;

        Sample(Status status, long bytes, long start, long expiry) {
            value = new Reading(status, bytes, start);
            expired = bytes < 0 ? value : new Reading(Status.STALE, bytes, start);
            this.expiry = expiry;
        }

        Reading at(long now) {
            return now - value.sampledAtNanos() >= expiry ? expired : value;
        }
    }

    private static Sample empty(Status status) {
        return new Sample(status, -1, 0, 0);
    }

    private static final Sample UNKNOWN = empty(Status.UNKNOWN);
    private static final Sample FAILED = empty(Status.FAILED);
    private static final Sample CLOSED = empty(Status.CLOSED);

    final class Handle implements DiskSpaceHealth {
        final Path path;
        final long warning;
        final Store store;
        Growth growth;
        long generation;
        Status reported = Status.UNKNOWN;
        volatile Sample current;

        Handle(Path path, long warning, Store store, Growth growth, Sample initial) {
            this.path = path;
            this.warning = warning;
            this.store = store;
            this.growth = growth;
            this.current = initial;
        }

        public Reading reading() {
            return current.at(clock.getAsLong());
        }

        public void growth(Growth next) {
            Objects.requireNonNull(next);
            synchronized (DiskSpaceHealthRegistry.this) {
                if (store == null || !store.handles.contains(this) || growth == next) return;
                growth = next;
                invalidate(this);
            }
        }

        public void close() {
            synchronized (DiskSpaceHealthRegistry.this) {
                if (current == CLOSED) return;
                current = CLOSED;
                if (store != null && store.handles.remove(this)) {
                    handleCount--;
                    releaseIfIdle(store);
                }
            }
        }
    }

    private final class Store {
        final FileStore fs;
        final List<Handle> handles = new ArrayList<>();
        boolean running, requested = true;
        long started;

        Store(FileStore fs) {
            this.fs = fs;
        }
    }

    private final LongSupplier clock;
    private final Resolver resolver;
    private final int storeLimit, handleLimit;
    private final Map<FileStore, Store> stores = new HashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private boolean closed;
    private ScheduledFuture<?> ticker;
    private int handleCount;

    DiskSpaceHealthRegistry() {
        this(System::nanoTime, Files::getFileStore, 16, 256);
    }

    DiskSpaceHealthRegistry(
            LongSupplier clock, Resolver resolver, int storeLimit, int handleLimit) {
        this.clock = clock;
        this.resolver = resolver;
        this.storeLimit = storeLimit;
        this.handleLimit = handleLimit;
        workers =
                (ThreadPoolExecutor)
                        Executors.newFixedThreadPool(
                                storeLimit, new NamedThreadFactory("disk~usable~probe", true));
        workers.setKeepAliveTime(1, TimeUnit.MINUTES);
        workers.allowCoreThreadTimeOut(true);
        timer =
                Executors.newSingleThreadScheduledExecutor(
                        new NamedThreadFactory("disk~usable~timer", true));
    }

    DiskSpaceHealth register(Path path, long warning, Growth growth, boolean disabled) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(growth);
        if (warning < 0) throw new IllegalArgumentException("Negative warning threshold");
        if (disabled) return new Handle(path, warning, null, growth, empty(Status.DISABLED));
        FileStore fs;
        try {
            fs = resolver.store(path);
        } catch (IOException | SecurityException e) {
            return new Handle(path, warning, null, growth, FAILED);
        }
        synchronized (this) {
            if (closed) return new Handle(path, warning, null, growth, CLOSED);
            Store store = stores.get(fs);
            if (handleCount == handleLimit || (store == null && stores.size() == storeLimit))
                return new Handle(path, warning, null, growth, empty(Status.UNAVAILABLE));
            if (store == null) {
                store = new Store(fs);
                stores.put(fs, store);
            }
            Handle h = new Handle(path, warning, store, growth, UNKNOWN);
            store.handles.add(h);
            handleCount++;
            store.requested = true;
            if (ticker == null)
                ticker = timer.scheduleAtFixedRate(this::tick, 0, 50, TimeUnit.MILLISECONDS);
            schedule(store);
            return h;
        }
    }

    private void invalidate(Handle h) {
        h.generation++;
        h.current = UNKNOWN;
        h.store.requested = true;
        schedule(h.store);
    }

    synchronized void tick() {
        long now = clock.getAsLong();
        for (Store s : stores.values()) {
            long interval = Growth.ACCOUNTED.refreshNanos;
            for (Handle h : s.handles) {
                Status status = h.current.at(now).status();
                reportTransition(h, status);
                interval = Math.min(interval, h.growth.refreshNanos);
                if (status == Status.FAILED || status == Status.STALE)
                    interval = Math.min(interval, Growth.SPARSE_OR_UNKNOWN.refreshNanos);
            }
            if (s.requested || now - s.started >= interval) schedule(s);
        }
    }

    private void reportTransition(Handle h, Status status) {
        if (status == h.reported) return;
        if (status == Status.LOW || status == Status.STALE || status == Status.FAILED)
            Jvm.warn().on(DiskSpaceHealth.class, "Writer disk health " + status + " for " + h.path);
        else if (status == Status.HEALTHY && h.reported != Status.UNKNOWN)
            Jvm.startup().on(DiskSpaceHealth.class, "Writer disk health recovered for " + h.path);
        h.reported = status;
    }

    // Reserve a slot before submitting. A native call may ignore interrupts and outlive its handle.
    private void schedule(Store s) {
        if (s.running || s.handles.isEmpty() || closed) return;
        s.running = true;
        s.requested = false;
        s.started = clock.getAsLong();
        long start = s.started;
        Map<Handle, Long> batch = new HashMap<>();
        for (Handle h : s.handles) batch.put(h, h.generation);
        workers.execute(() -> probe(s, batch, start));
    }

    private void probe(Store s, Map<Handle, Long> batch, long start) {
        try {
            long bytes;
            try {
                bytes = s.fs.getUsableSpace();
            } catch (IOException | RuntimeException e) {
                bytes = -1;
            }
            Map<Path, Status> mounts = new HashMap<>();
            for (Map.Entry<Handle, Long> entry : batch.entrySet()) {
                Handle h = entry.getKey();
                Status mount =
                        mounts.computeIfAbsent(
                                h.path,
                                p -> {
                                    try {
                                        return s.fs.equals(resolver.store(p))
                                                ? Status.HEALTHY
                                                : Status.REMOUNTED;
                                    } catch (IOException | RuntimeException e) {
                                        return Status.FAILED;
                                    }
                                });
                synchronized (this) {
                    if (h.current == CLOSED || h.generation != entry.getValue()) continue;
                    if (mount == Status.REMOUNTED) {
                        h.current = empty(Status.REMOUNTED);
                        s.handles.remove(h);
                        handleCount--;
                        Jvm.warn()
                                .on(
                                        DiskSpaceHealth.class,
                                        "Writer filesystem changed for " + h.path);
                    } else if (bytes < 0 || mount == Status.FAILED) h.current = FAILED;
                    else
                        h.current =
                                new Sample(
                                        bytes <= h.warning ? Status.LOW : Status.HEALTHY,
                                        bytes,
                                        start,
                                        h.growth.expiryNanos);
                }
            }
        } finally {
            synchronized (this) {
                s.running = false;
                releaseIfIdle(s);
                if (s.requested) schedule(s);
            }
        }
    }

    private void releaseIfIdle(Store s) {
        if (s.handles.isEmpty() && !s.running) stores.remove(s.fs);
        if (stores.isEmpty() && ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }

    synchronized void close() {
        closed = true;
        for (Store s : stores.values()) {
            for (Handle h : s.handles) h.current = CLOSED;
            s.handles.clear();
        }
        stores.clear();
        handleCount = 0;
        timer.shutdownNow();
        workers.shutdownNow();
    }
}
