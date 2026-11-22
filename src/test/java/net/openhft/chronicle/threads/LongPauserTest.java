/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pausing behaviour of {@link LongPauser}.
 *
 * <p>These tests ensure that:
 * <ul>
 *   <li>{@link LongPauser#unpause()} releases a thread blocked in
 *       {@link LongPauser#pause()} promptly.</li>
 *   <li>{@link LongPauser#asyncPause()} waits for roughly the configured
 *       duration before clearing.</li>
 *   <li>{@link LongPauser#reset()} cancels any pending asynchronous
 *       pause.</li>
 * </ul>
 */
class LongPauserTest extends ThreadsTestCommon {

    @Test
    void unpauseStopsPausing() throws InterruptedException {
        final int pauseMillis = 1_000;
        final LongPauser pauser = new LongPauser(0, 0, pauseMillis, pauseMillis, TimeUnit.MILLISECONDS);
        final CountDownLatch started = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            started.countDown();
            pauser.pause();
        });
        thread.start();
        started.await(50, TimeUnit.MILLISECONDS);
        Jvm.pause(10);  // give the thread some time to park
        pauser.unpause();
        final long startNs = System.nanoTime();
        thread.join();
        final long timeTakenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        assertTrue(timeTakenMs < pauseMillis / 5, "Took " + timeTakenMs + " to stop");
    }

    @ParameterizedTest
    @EnumSource(value = TimeUnit.class, names = {"NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS"})
    void testLongAsyncPauser(TimeUnit unit) {
        final ControlledLongPauser pauser = new ControlledLongPauser(unit);
        // The old wall-clock tolerance measured descheduling and accidentally converted
        // nanoseconds to the requested unit. Check the actual unit conversion and boundary.
        for (int i = 0; i < 100; i++) {
            pauser.asyncPause();
            assertTrue(pauser.asyncPausing());
            pauser.now += unit.toNanos(1) - 1;
            assertTrue(pauser.asyncPausing());
            pauser.now++;
            assertFalse(pauser.asyncPausing());
            pauser.reset();
            assertFalse(pauser.asyncPausing());
        }
    }

    @Test
    void asyncPauseIsResetOnReset() {
        final LongPauser longPauser = new ControlledLongPauser(TimeUnit.SECONDS);
        longPauser.asyncPause();
        assertTrue(longPauser.asyncPausing());
        longPauser.reset();
        assertFalse(longPauser.asyncPausing());
    }

    private static final class ControlledLongPauser extends LongPauser {
        long now = TimeUnit.SECONDS.toNanos(1);

        ControlledLongPauser(TimeUnit unit) {
            super(0, 0, 1, 1, unit);
        }

        @Override
        long nanoTime() {
            return now;
        }
    }
}
