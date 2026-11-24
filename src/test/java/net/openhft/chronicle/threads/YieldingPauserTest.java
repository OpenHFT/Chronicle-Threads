/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class YieldingPauserTest extends ThreadsTestCommon {

    // Elapsed wall time also includes descheduling, GC and exception construction.
    // A controlled clock checks the 100 ms contract exactly, including its strict boundary.
    @Test
    void pause() throws TimeoutException {
        ControlledPauser pauser = new ControlledPauser(0);
        pauser.pause(100, TimeUnit.MILLISECONDS);
        pauser.now += TimeUnit.MILLISECONDS.toNanos(100);
        pauser.pause(100, TimeUnit.MILLISECONDS);
        pauser.now++;
        assertThrows(TimeoutException.class, () -> pauser.pause(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void resetStartsAnotherFullDeadline() throws TimeoutException {
        ControlledPauser pauser = new ControlledPauser(0);
        pauser.pause(100, TimeUnit.MILLISECONDS);
        pauser.now += TimeUnit.MILLISECONDS.toNanos(100) + 1;
        assertThrows(TimeoutException.class, () -> pauser.pause(100, TimeUnit.MILLISECONDS));
        pauser.reset();
        pauser.pause(100, TimeUnit.MILLISECONDS);
        pauser.now += TimeUnit.MILLISECONDS.toNanos(100);
        pauser.pause(100, TimeUnit.MILLISECONDS);
        pauser.now++;
        assertThrows(TimeoutException.class, () -> pauser.pause(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void busyCallsStartTheDeadlineBeforeYielding() throws TimeoutException {
        ControlledPauser pauser = new ControlledPauser(3);
        pauser.pause(100, TimeUnit.MILLISECONDS);
        pauser.now += TimeUnit.MILLISECONDS.toNanos(100) + 1;
        pauser.pause(100, TimeUnit.MILLISECONDS);
        assertEquals(0, pauser.yields);
        assertThrows(TimeoutException.class, () -> pauser.pause(100, TimeUnit.MILLISECONDS));
        assertEquals(1, pauser.yields);
    }

    @Test
    void timeSpentYieldingCountsTowardsDeadline() {
        ControlledPauser pauser = new ControlledPauser(0);
        pauser.yieldNanos = TimeUnit.MILLISECONDS.toNanos(100) + 1;
        assertThrows(TimeoutException.class, () -> pauser.pause(100, TimeUnit.MILLISECONDS));
        assertEquals(1, pauser.yields);
    }

    @Test
    void productionClockExpires() {
        YieldingPauser pauser = new YieldingPauser(0);
        // A negative limit must expire on the first yielding call for a monotonic clock.
        // This covers the production clock implementation without a scheduler deadline.
        assertThrows(TimeoutException.class, () -> pauser.pause(-1, TimeUnit.NANOSECONDS));
    }

    private static final class ControlledPauser extends YieldingPauser {
        private long now = TimeUnit.SECONDS.toNanos(1);
        private long yieldNanos;
        private int yields;

        ControlledPauser(int minBusy) {
            super(minBusy);
        }

        @Override
        long nanoTime() {
            return now;
        }

        @Override
        void yield0() {
            yields++;
            now += yieldNanos;
        }
    }
}
