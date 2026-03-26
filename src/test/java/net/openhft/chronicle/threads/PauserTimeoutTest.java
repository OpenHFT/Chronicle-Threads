/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link Pauser#pause(long, java.util.concurrent.TimeUnit)} with a
 * timeout across several implementations.  Pausers that support the timeout
 * contract are called repeatedly until half the period has passed without a
 * {@link TimeoutException}.  After the interval expires, the next call must
 * throw a {@link TimeoutException}.  Pausers that do not implement this
 * behaviour are expected to throw {@link UnsupportedOperationException} when a
 * timeout is supplied.
 */
class PauserTimeoutTest extends ThreadsTestCommon {
    private Pauser[] pausersSupportTimeout = {
            Pauser.balanced(),
            Pauser.sleepy(),
            new BusyTimedPauser(),
            new YieldingPauser(0),
            new LongPauser(0, 0, 1, 10, TimeUnit.MILLISECONDS),
//            new MilliPauser(1)
    };
    private Pauser[] pausersDontSupportTimeout = {
            BusyPauser.INSTANCE};

    /**
     * Confirms that pausers honour the timeout parameter.  Each pauser is
     * called in a loop until half the timeout has elapsed and should not throw.
     * Once the timeout has expired the next call must raise
     * {@link TimeoutException}.
     */
    @Test
    void pausersSupportTimeout() {
        int timeoutNS = 100_000_000;
        for (Pauser p : pausersSupportTimeout) {
            long start = System.nanoTime();
            do try {
                p.pause(timeoutNS, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                fail(p + " timed out");
            } while (System.nanoTime() < start + timeoutNS / 2);
            while (System.nanoTime() < start + timeoutNS * 5 / 4) ;
            assertThrows(TimeoutException.class, () -> p.pause(timeoutNS, TimeUnit.NANOSECONDS),
                    p + " did not timeoutNS");
        }
    }

    /**
     * Checks that pausers without timeout capability throw
     * {@link UnsupportedOperationException} when a timeout is supplied.
     */
    @Test
    void pausersDontSupportTimeout() {
        for (Pauser p : pausersDontSupportTimeout) {
            assertThrows(UnsupportedOperationException.class, () -> p.pause(100, TimeUnit.MILLISECONDS),
                    p + " did not throw");
        }
    }
}
