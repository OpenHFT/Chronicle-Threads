//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.junit.jupiter.api.Test;

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

    @Test
    void testLongAsyncPauser() {
        final LongPauser pauser = new LongPauser(0, 0, 1, 1, TimeUnit.MILLISECONDS);
        boolean failedOnce = false;
        for (int i = 0; i < 100; i++) {
            try {
                pauser.asyncPause();
                testUntilUnpaused(pauser, 1, TimeUnit.MILLISECONDS);
                pauser.reset();
                testUntilUnpaused(pauser, 0, TimeUnit.MILLISECONDS);
            } catch (AssertionError e) {
                if (failedOnce)
                    throw e;
                failedOnce = true;
            }
        }
    }

    @Test
    void asyncPauseIsResetOnReset() {
        final LongPauser longPauser = new LongPauser(0, 0, 1, 1, TimeUnit.SECONDS);
        longPauser.asyncPause();
        assertTrue(longPauser.asyncPausing());
        longPauser.reset();
        assertFalse(longPauser.asyncPausing());
    }

    private static void testUntilUnpaused(LongPauser pauser, int n, TimeUnit timeUnit) {
        long timeNS = timeUnit.convert(n, TimeUnit.NANOSECONDS);
        long start = System.nanoTime();
        while (pauser.asyncPausing()) {
            if (System.nanoTime() > start + timeNS + 100_000_000)
                fail();
        }
        long time = System.nanoTime() - start;
        final int delta = 11_000_000;
        assertEquals(timeNS + delta, time, delta);
    }
}
