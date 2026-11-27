/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import static net.openhft.chronicle.threads.DiskSpaceHealth.Growth.*;
import static net.openhft.chronicle.threads.DiskSpaceHealth.Status.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.LogLevel;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.core.time.SystemTimeProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

class DiskSpaceHealthTest {
    private static final long MIB = 1L << 20;
    private final Path a = Paths.get("a"), b = Paths.get("b");
    private final AtomicLong now = new AtomicLong();
    private DiskSpaceHealthRegistry monitor;

    @AfterEach
    void close() {
        if (monitor != null) monitor.close();
    }

    private void advance(long millis) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        monitor.tick();
    }

    private static void await(BooleanSupplier test) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!test.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) fail("Probe timed out");
            Thread.sleep(1);
        }
    }

    @Test
    void optingInPreservesLegacyUnallocatedSpaceCallbacks() throws Exception {
        long gib = 1024 * MIB;
        DiskSpaceMonitor legacy = DiskSpaceMonitor.INSTANCE;
        SetTimeProvider time = new SetTimeProvider();
        int priorThreshold = legacy.getThresholdPercentage();
        legacy.setTimeProvider(time);
        legacy.setThresholdPercentage(5);
        try {
            for (long reserved : new long[] {0, 140 * gib}) {
                for (int available : new int[] {513, 512, 256, 255, 200, 1, 0}) {
                    for (boolean optIn : new boolean[] {false, true}) {
                        FileStore fs = mock(FileStore.class);
                        when(fs.toString()).thenReturn("reserved-fixture");
                        when(fs.getTotalSpace()).thenReturn(2048 * gib);
                        when(fs.getUsableSpace()).thenReturn(available * MIB);
                        when(fs.getUnallocatedSpace()).thenReturn(available * MIB + reserved);
                        DiskSpaceMonitor.DiskAttributes disk = legacy.new DiskAttributes(fs);
                        Map<ExceptionKey, Integer> log = Jvm.recordExceptions();
                        try {
                            if (optIn) {
                                monitor = new DiskSpaceHealthRegistry(now::get, p -> fs, 1, 1);
                                try (DiskSpaceHealth h =
                                        monitor.register(a, 512 * MIB, SPARSE_OR_UNKNOWN, false)) {
                                    await(() -> h.reading().usableBytes() == available * MIB);
                                    assertEquals(
                                            available <= 512 ? LOW : HEALTHY, h.reading().status());
                                }
                            }
                            for (int second = 0; second < 60; second += 10) {
                                disk.run();
                                time.advanceMillis(10000);
                            }
                            int errors = 0, warnings = 0;
                            for (Map.Entry<ExceptionKey, Integer> entry : log.entrySet()) {
                                ExceptionKey key = entry.getKey();
                                if (key.clazz() != DiskSpaceMonitor.class
                                        || !key.message().startsWith("your disk reserved-fixture "))
                                    continue;
                                if (key.level() == LogLevel.WARN) warnings += entry.getValue();
                                if (key.level() == LogLevel.ERROR) errors += entry.getValue();
                            }
                            assertEquals(reserved == 0 && available >= 200 ? 6 : 0, warnings);
                            assertEquals(reserved == 0 && available < 200 ? 6 : 0, errors);
                        } finally {
                            Jvm.resetExceptionHandlers();
                            if (monitor != null) monitor.close();
                        }
                    }
                }
            }
        } finally {
            legacy.setTimeProvider(SystemTimeProvider.INSTANCE);
            legacy.setThresholdPercentage(priorThreshold);
        }
    }

    @Test
    void usableBytesDriveThresholdEvenWithReservedBlocks() throws Exception {
        FileStore fs = mock(FileStore.class);
        AtomicLong usable = new AtomicLong(513 * MIB);
        when(fs.getUsableSpace()).thenAnswer(i -> usable.get());
        when(fs.getUnallocatedSpace()).thenReturn(140_000 * MIB);
        monitor = new DiskSpaceHealthRegistry(now::get, path -> fs, 1, 1);
        try (DiskSpaceHealth h = monitor.register(a, 512 * MIB, SPARSE_OR_UNKNOWN, false)) {
            await(() -> h.reading().status() == HEALTHY);
            for (int value : new int[] {512, 256, 255, 200, 1, 0, 513}) {
                usable.set(value * MIB);
                advance(750);
                await(() -> h.reading().usableBytes() == value * MIB);
                assertEquals(value <= 512 ? LOW : HEALTHY, h.reading().status());
            }
            DiskSpaceHealth.Reading snapshot = h.reading();
            for (int n = 0; n < 1000; n++) assertSame(snapshot, h.reading());
            verify(fs, never()).getUnallocatedSpace();
        }
    }

    @Test
    void blockedProbeExpiresOnlyItsOwnFilesystemAndCannotReviveClosedHandle() throws Exception {
        FileStore slow = mock(FileStore.class), fast = mock(FileStore.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(slow.getUsableSpace())
                .thenAnswer(
                        i -> {
                            if (calls.incrementAndGet() == 2) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            }
                            return MIB;
                        });
        when(fast.getUsableSpace()).thenReturn(2 * MIB);
        monitor = new DiskSpaceHealthRegistry(now::get, p -> p.equals(a) ? slow : fast, 2, 4);
        DiskSpaceHealth left = monitor.register(a, 1, SPARSE_OR_UNKNOWN, false);
        DiskSpaceHealth right = monitor.register(b, 1, SPARSE_OR_UNKNOWN, false);
        try {
            await(() -> left.reading().status() == HEALTHY && right.reading().status() == HEALTHY);
            advance(750);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            advance(2000);
            assertEquals(STALE, left.reading().status());
            await(() -> right.reading().sampledAtNanos() == now.get());
            assertEquals(HEALTHY, right.reading().status());
            assertEquals(2, calls.get());
            left.close();
        } finally {
            release.countDown();
        }
        assertEquals(CLOSED, left.reading().status());
        right.close();
    }

    @Test
    void lateResultAndFailureCannotAdmitWritesAndFreshReadRecovers() throws Exception {
        FileStore fs = mock(FileStore.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(fs.getUsableSpace())
                .thenAnswer(
                        i -> {
                            switch (calls.incrementAndGet()) {
                                case 1:
                                    entered.countDown();
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                    break;
                                case 2:
                                    assertTrue(second.await(5, TimeUnit.SECONDS));
                                    throw new IOException("lost mount");
                                default:
                                    break;
                            }
                            return MIB;
                        });
        monitor = new DiskSpaceHealthRegistry(now::get, p -> fs, 1, 1);
        DiskSpaceHealth h = monitor.register(a, 1, SPARSE_OR_UNKNOWN, false);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(UNKNOWN, h.reading().status());
            advance(1100);
            release.countDown();
            await(() -> h.reading().status() == STALE);
            second.countDown();
            await(() -> h.reading().status() == FAILED);
            advance(751);
            await(() -> h.reading().status() == HEALTHY);
        } finally {
            release.countDown();
            second.countDown();
            h.close();
        }
    }

    @Test
    void accountedDeadlineAndSwitchToSparseIgnoreLegacyBackoff() throws Exception {
        FileStore fs = mock(FileStore.class);
        when(fs.getUsableSpace()).thenReturn(1L << 40);
        monitor = new DiskSpaceHealthRegistry(now::get, p -> fs, 1, 2);
        try (DiskSpaceHealth h = monitor.register(a, 1, ACCOUNTED, false)) {
            await(() -> h.reading().status() == HEALTHY);
            advance(589_999);
            verify(fs, times(1)).getUsableSpace();
            now.set(TimeUnit.MINUTES.toNanos(10));
            assertEquals(STALE, h.reading().status());
            monitor.tick();
            await(() -> h.reading().sampledAtNanos() == now.get());
            long before = now.incrementAndGet();
            h.growth(SPARSE_OR_UNKNOWN);
            await(() -> h.reading().sampledAtNanos() == before);
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assertEquals(STALE, h.reading().status());
        }
    }

    @Test
    void closingTheLastHandleKeepsABlockedWorkerCountedUntilItExits() throws Exception {
        FileStore slow = mock(FileStore.class), fast = mock(FileStore.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(slow.getUsableSpace())
                .thenAnswer(
                        i -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return MIB;
                        });
        when(fast.getUsableSpace()).thenReturn(MIB);
        monitor = new DiskSpaceHealthRegistry(now::get, p -> p.equals(a) ? slow : fast, 1, 2);
        DiskSpaceHealth old = monitor.register(a, 0, ACCOUNTED, false);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            old.close();
            assertEquals(UNAVAILABLE, monitor.register(b, 0, ACCOUNTED, false).reading().status());
        } finally {
            release.countDown();
        }
        AtomicReference<DiskSpaceHealth> admitted = new AtomicReference<>();
        await(
                () -> {
                    DiskSpaceHealth next = monitor.register(b, 0, ACCOUNTED, false);
                    if (next.reading().status() == UNAVAILABLE) return false;
                    admitted.set(next);
                    return true;
                });
        await(() -> admitted.get().reading().status() == HEALTHY);
        assertEquals(CLOSED, old.reading().status());
        verify(slow, times(1)).getUsableSpace();
        admitted.get().close();
    }

    @Test
    void boundsSharingRemountAndDisabledRegistration() throws Exception {
        FileStore first = mock(FileStore.class), next = mock(FileStore.class);
        when(first.getUsableSpace()).thenReturn(MIB);
        when(next.getUsableSpace()).thenReturn(2 * MIB);
        AtomicReference<FileStore> mount = new AtomicReference<>(first);
        AtomicBoolean disabledTouched = new AtomicBoolean();
        monitor =
                new DiskSpaceHealthRegistry(
                        now::get,
                        p -> {
                            if (p.equals(Paths.get("disabled"))) disabledTouched.set(true);
                            return p.equals(a) ? mount.get() : first;
                        },
                        1,
                        2);
        DiskSpaceHealth h = monitor.register(a, 1, SPARSE_OR_UNKNOWN, false);
        DiskSpaceHealth alias = monitor.register(b, 2 * MIB, SPARSE_OR_UNKNOWN, false);
        await(() -> h.reading().status() == HEALTHY && alias.reading().status() == LOW);
        assertEquals(UNAVAILABLE, monitor.register(b, 0, ACCOUNTED, false).reading().status());
        mount.set(next);
        advance(750);
        await(() -> h.reading().status() == REMOUNTED);
        assertEquals(LOW, alias.reading().status());
        h.close();
        assertEquals(UNAVAILABLE, monitor.register(a, 0, ACCOUNTED, false).reading().status());
        alias.close();
        await(
                () -> {
                    DiskSpaceHealth retry = monitor.register(a, 0, ACCOUNTED, false);
                    boolean admitted = retry.reading().status() != UNAVAILABLE;
                    retry.close();
                    return admitted;
                });
        assertEquals(
                DISABLED,
                monitor.register(Paths.get("disabled"), 1, ACCOUNTED, true).reading().status());
        assertFalse(disabledTouched.get());
    }
}
