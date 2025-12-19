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
        Pauser pauser = Pauser.balanced();
        doTest(pauser);
        assertTrue(pauser.countPaused() > 0, "countPaused updated after pauses (" + pauser + ")");
    }

    @Test
    void balancedUpToMillis1() {
        Pauser pauser = Pauser.balancedUpToMillis(1);
        doTest(pauser);
        assertTrue(pauser.countPaused() > 0, "countPaused updated after pauses (" + pauser + ")");
    }

    @Test
    void busy() throws TimeoutException {
        Pauser pauser = BusyPauser.INSTANCE;
        assertEquals(0, pauser.countPaused(), "busy pauser countPaused starts at zero");
        assertEquals(0, pauser.timePaused(), "busy pauser timePaused starts at zero");
        pauser.pause();
        try {
            pauser.pause(1, TimeUnit.MILLISECONDS);
        } catch (UnsupportedOperationException ignored) {
            // BusyPauser does not support timed pauses; expected.
        }
        assertEquals(0, pauser.countPaused(), "busy pauser does not record pause count");
        pauser.unpause();
        assertTrue(pauser.isBusy(), "busy pauser reports isBusy");
    }

    @Test
    void millis1() {
        Pauser pauser = Pauser.millis(1);
        doTest(pauser, 200);
        assertTrue(pauser.countPaused() > 0, "countPaused updated after pauses (" + pauser + ")");
    }

    @Test
    void sleepy() {
        Pauser pauser = Pauser.sleepy();
        doTest(pauser, 200);
        assertTrue(pauser.countPaused() > 0, "countPaused updated after pauses (" + pauser + ")");
    }

    @Test
    void timedBusy() {
        Pauser pauser = Pauser.timedBusy();
        doTest(pauser);
        assertTrue(pauser.countPaused() > 0, "countPaused updated after pauses (" + pauser + ")");
    }

    @Test
    void yielding() {
        Pauser pauser = Pauser.yielding();
        doTest(pauser);
        assertTrue(pauser.countPaused() > 0, "countPaused updated after pauses (" + pauser + ")");
    }

    private void doTest(Pauser pauser) {
        doTest(pauser, 2000);
    }

    private void doTest(Pauser pauser, int count) {
        assertEquals(0, pauser.countPaused(), "countPaused starts at zero (" + pauser + ")");
        assertEquals(0, pauser.timePaused(), "timePaused starts at zero (" + pauser + ")");
        for (int i = 1; i < count; i++) {
            pauser.pause();
            assertEquals(i, pauser.countPaused(), "countPaused increments (" + pauser + ")");
        }
        pauser.unpause();
        assertEquals(pauser.getClass().getSimpleName().contains("Busy"),
                pauser.isBusy(),
                "isBusy matches pauser type (" + pauser + ")");
    }
}
