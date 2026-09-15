/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.OS;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class YieldingPauserTest extends ThreadsTestCommon {

    @Test
    void pause() {
        final int pauseTimeMillis = 100;
        final YieldingPauser tp = new YieldingPauser(pauseTimeMillis);
        for (int i = 0; i < 10; i++) {
            final long start = System.currentTimeMillis();
            while (true) {
                try {
                    tp.pause(pauseTimeMillis, TimeUnit.MILLISECONDS);
                    if (System.currentTimeMillis() - start > 200)
                        fail();
                } catch (TimeoutException e) {
                    final long time = System.currentTimeMillis() - start;
                    // delta used to be 5 for Linux but occasionally we see it blow in Continuous Integration
                    // a delta of 20 was used here, however in some situations in CI that was not sufficient:
                    // org.opentest4j.AssertionFailedError: expected: <100.0> but was: <126.0>
                    int delta = 30;
                    // macOS CI has taken 176 ms to observe the timeout; retain the existing lower bound.
                    final int maxTimeMillis = OS.isMacOSX() ? 180 : pauseTimeMillis + delta;
                    assertTrue(time >= pauseTimeMillis - delta && time <= maxTimeMillis,
                            () -> "Expected " + (pauseTimeMillis - delta) + " to " + maxTimeMillis
                                    + " ms but was " + time + " ms");
                    tp.reset();
                    break;
                }
            }
        }
    }
}
