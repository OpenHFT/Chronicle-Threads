/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the behaviour of the various {@link Pauser} implementations.
 * <p>
 * The suite verifies that pause counters start at zero and increment with each
 * call to {@link Pauser#pause()}. After {@link Pauser#unpause()} the test
 * asserts whether {@link Pauser#isBusy()} matches the pauser type. The
 * {@link BusyPauser} is additionally checked to confirm it does not record
 * pause counts and rejects timed pauses.
 */

class PauserTest extends ThreadsTestCommon {

    @Test
    void balanced() {
        doTest(Pauser.balanced());
    }

    @Test
    void balancedUpToMillis1() {
        doTest(Pauser.balancedUpToMillis(1));
    }

    @Test
    void busy() throws TimeoutException {
        Pauser pauser = BusyPauser.INSTANCE;
        assertEquals(0, pauser.countPaused());
        assertEquals(0, pauser.timePaused());
        pauser.pause();
        try {
            pauser.pause(1, TimeUnit.MILLISECONDS);
        } catch (UnsupportedOperationException ignored) {
            // BusyPauser does not support timed pauses; expected.
        }
        assertEquals(0, pauser.countPaused());
        pauser.unpause();
        assertTrue(pauser.isBusy());
    }

    @Test
    void millis1() {
        doTest(Pauser.millis(1), 200);
    }

    @Test
    void sleepy() {
        doTest(Pauser.sleepy(), 200);
    }

    @Test
    void timedBusy() {
        doTest(Pauser.timedBusy());
    }

    @Test
    void yielding() {
        doTest(Pauser.yielding());
    }

    private void doTest(Pauser pauser) {
        doTest(pauser, 2000);
    }

    private void doTest(Pauser pauser, int count) {
        assertEquals(0, pauser.countPaused());
        assertEquals(0, pauser.timePaused());
        for (int i = 1; i < count; i++) {
            pauser.pause();
            assertEquals(i, pauser.countPaused());
        }
        pauser.unpause();
        assertEquals(pauser.getClass().getSimpleName().contains("Busy"),
                pauser.isBusy());
    }
}
