/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
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
                assertAsyncPausesFor(pauser, pauser::asyncPause, TimeUnit.MILLISECONDS.toNanos(1));
                assertAsyncPausesFor(pauser, pauser::reset, 0);
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

    /**
     * Runs {@code action} then spins on {@link LongPauser#asyncPausing()} until it clears, asserting the elapsed
     * time is at least {@code expectedNs} and not much more.
     *
     * <p>A stall (GC, safepoint, descheduling) can stretch the elapsed time in two places:
     * <ul>
     *   <li>inside {@code action}, before it samples the clock to set the deadline</li>
     *   <li>between the last check that still saw pausing and the end of the loop</li>
     * </ul>
     * Both gaps are measured and added to the fixed tolerance.
     */
    private static void assertAsyncPausesFor(LongPauser pauser, Runnable action, long expectedNs) {
        final long toleranceNs = 1_000;
        final long timeoutNs = 100_000_000;

        // We time the action in case GC etc. makes it unexpectedly slow
        final long start = System.nanoTime();
        action.run();
        final long actionEnd = System.nanoTime();
        final long actionDurationNs = actionEnd - start;

        // We time the gaps between calls to pauser.asyncPausing() in case the final one is unexpectedly long
        long lastPausingNs = actionEnd;
        while(true) {
            final long beforeCheckNs = System.nanoTime();
            if (!pauser.asyncPausing())
                break;
            lastPausingNs = beforeCheckNs;
            // Fail if the loop does not exit in a reasonable time
            if (beforeCheckNs - start - actionDurationNs > expectedNs + timeoutNs)
                fail("Still pausing after " + (beforeCheckNs - start) / 1_000 + " us, action took " + actionDurationNs / 1_000 + " us");
        }
        final long end = System.nanoTime();
        final long finalGapNs = end - lastPausingNs;
        final long elapsedNs = end - start;

        // The timing uncertainty is the sum of:
        //   actionDurationNs - because we are not sure exactly when any pause timer was started
        //   finalGapNs - because the pause may have ended this long ago when we finally detected it had ended
        //   toleranceNs - accounts for rounding errors, timing jitter etc.
        final long uncertaintyNs = toleranceNs + actionDurationNs + finalGapNs;
        final long delta = uncertaintyNs / 2;
        assertEquals(expectedNs + delta, elapsedNs, delta,
                () -> "elapsed " + elapsedNs / 1_000 + " us, expected " + expectedNs / 1_000
                        + " us, setup gap " + actionDurationNs / 1_000 + " us, final gap " + finalGapNs / 1_000 + " us");
    }
}
