/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

/** A writer's registration with the disk monitor. Reading the handle performs no filesystem I/O. */
public interface DiskSpaceHealth extends Closeable {
    enum Status {
        UNKNOWN,
        HEALTHY,
        LOW,
        STALE,
        FAILED,
        REMOUNTED,
        UNAVAILABLE,
        DISABLED,
        CLOSED
    }

    /** Use ACCOUNTED only if growth from every writer sharing the store is bounded. */
    enum Growth {
        SPARSE_OR_UNKNOWN(750, 1_000),
        ACCOUNTED(590_000, 600_000);
        final long refreshNanos, expiryNanos;

        Growth(long refreshMillis, long expiryMillis) {
            this.refreshNanos = TimeUnit.MILLISECONDS.toNanos(refreshMillis);
            this.expiryNanos = TimeUnit.MILLISECONDS.toNanos(expiryMillis);
        }
    }

    /** Immutable observation. Re-read the handle before each admission decision. */
    final class Reading {
        private final Status status;
        private final long usableBytes;
        private final long sampledAtNanos;

        Reading(Status status, long usableBytes, long sampledAtNanos) {
            this.status = status;
            this.usableBytes = usableBytes;
            this.sampledAtNanos = sampledAtNanos;
        }

        public Status status() {
            return status;
        }

        /** Usable bytes returned by the last successful probe, or -1 when unavailable. */
        public long usableBytes() {
            return usableBytes;
        }

        /** Probe start in the System.nanoTime() time base. Defined only when usableBytes() >= 0. */
        public long sampledAtNanos() {
            return sampledAtNanos;
        }
    }

    Reading reading();

    /** Change the growth contract before using a sparse mapping or allowing unknown growth. */
    void growth(Growth growth);

    @Override
    void close();
}
