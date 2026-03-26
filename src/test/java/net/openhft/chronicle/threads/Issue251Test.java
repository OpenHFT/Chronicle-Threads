/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Confirms the stable {@code toString} output for each built-in pauser.
 * Verifies the fix for issue {@code #251} where descriptions were inconsistent.
 */
class Issue251Test {
    @Test
    void toString_timedBusyVariants() {
        assertEquals("PauserMode.timedBusy", new BusyTimedPauser().toString());
        assertEquals("PauserMode.timedBusy", PauserMode.timedBusy.get().toString());
        assertEquals("PauserMode.timedBusy", Pauser.timedBusy().toString());
    }

    @Test
    void toString_busyVariants() {
        assertEquals("PauserMode.busy", BusyPauser.INSTANCE.toString());
        assertEquals("PauserMode.busy", PauserMode.busy.get().toString());
        assertEquals("PauserMode.busy", Pauser.busy().toString());
    }

    @Test
    void toString_balancedFromMode() {
        assertEquals("PauserMode.balanced", PauserMode.balanced.get().toString());
    }

    @Test
    void toString_balanced() {
        assertEquals("PauserMode.balanced", Pauser.balanced().toString());
    }

    @Test
    void toString_millis3ms() {
        assertEquals("Pauser.millis(3)", Pauser.millis(3).toString());
    }

    @Test
    void toString_milli1and10() {
        assertEquals("Pauser.milli(1, 10)", Pauser.millis(1, 10).toString());
    }

    @Test
    void toString_balanced2ms() {
        assertEquals("Pauser.balancedUpToMillis(2)", Pauser.balancedUpToMillis(2).toString());
    }

    @Test
    void toString_yieldingNoParams() {
        assertEquals("PauserMode.yielding", Pauser.yielding().toString());
    }

    @Test
    void toString_yieldingMinBusy3() {
        assertEquals("YieldingPauser{minBusy=3}", Pauser.yielding(3).toString());
    }

    @Test
    void toString_milliMode() {
        assertEquals("PauserMode.milli", PauserMode.milli.get().toString());
    }

    @Test
    void toString_yieldingMode() {
        assertEquals("PauserMode.yielding", PauserMode.yielding.get().toString());
    }

    @Test
    void toString_sleepyMode() {
        assertEquals("PauserMode.sleepy", PauserMode.sleepy.get().toString());
    }

    @Test
    void toString_yieldingMinBusy7() {
        assertEquals("YieldingPauser{minBusy=7}", new YieldingPauser(7).toString());
    }

    @Test
    void toString_yieldingMinBusy1() {
        assertEquals("YieldingPauser{minBusy=1}", new YieldingPauser(1).toString());
    }

    @Test
    void toString_millis7ms() {
        assertEquals("Pauser.millis(7)", new MilliPauser(7).toString());
    }
}
