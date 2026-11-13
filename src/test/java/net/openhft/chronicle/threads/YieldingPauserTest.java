/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class YieldingPauserTest extends ThreadsTestCommon {

    @Test
    void pause() {
        final long testTimeoutNs = 100_000_000;
        final long pauseTimeoutNs = 10_000;
        final long toleranceNs = 2_000;

        final YieldingPauser tp = new YieldingPauser(0);

        for (int i = 0; i < 10; i++) {
            // The pauser starts its timeout clock somewhere inside the first call
            final long startNs = System.nanoTime();
            boolean timedOut = pauseTimesOut(tp, pauseTimeoutNs);
            final long firstCallEndNs = System.nanoTime();

            // Start of the last call that did not time out. That call read the clock after this point and saw
            // no timeout, so the timeout cannot have expired before it.
            long lastOkStartNs = startNs;
            while (!timedOut) {
                final long callStartNs = System.nanoTime();
                timedOut = pauseTimesOut(tp, pauseTimeoutNs);
                if (!timedOut) {
                    lastOkStartNs = callStartNs;
                    // This call started this long after the timeout clock and still did not time out, so the
                    // pauser is broken. A stall cannot trigger this, as it would make the call time out instead.
                    if (callStartNs - firstCallEndNs > pauseTimeoutNs + testTimeoutNs)
                        fail("Pauser did not time out within " + (callStartNs - firstCallEndNs) + " ns");
                }
            }
            final long endNs = System.nanoTime();
            final long elapsedNs = endNs - startNs;

            // The timing uncertainty is the sum of:
            //   setupGapNs - because we are not sure exactly when the timeout clock was started
            //   finalGapNs - because the timeout may have expired this long before we detected it
            //   toleranceNs - accounts for rounding errors, timing jitter etc.
            // A stall (GC, OS etc.) in either gap is measured, so it widens the window.
            final long setupGapNs = firstCallEndNs - startNs;
            final long finalGapNs = endNs - lastOkStartNs;
            final long delta = (toleranceNs + setupGapNs + finalGapNs) / 2;
            assertEquals(pauseTimeoutNs + delta, elapsedNs, delta,
                    () -> "elapsed " + elapsedNs + " ns, expected " + pauseTimeoutNs
                            + " ns, setup gap " + setupGapNs + " ns, final gap " + finalGapNs + " ns");
            tp.reset();
        }
    }

    private static boolean pauseTimesOut(TimingPauser pauser, long timeoutNs) {
        try {
            pauser.pause(timeoutNs, TimeUnit.NANOSECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        }
    }
}
