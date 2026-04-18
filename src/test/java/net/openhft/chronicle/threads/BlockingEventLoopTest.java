/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InterruptedRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a handler in a {@link BlockingEventLoop} is interrupted when
 * the loop is stopped while the calling thread continues unimpeded.
 */
class BlockingEventLoopTest extends ThreadsTestCommon {

    @Test
    void handlersAreInterruptedOnStop() throws TimeoutException {
        try (final BlockingEventLoop el = new BlockingEventLoop("test-blocking-loop")) {
            el.start();

            AtomicBoolean wasStoppedSuccessfully = new AtomicBoolean(false);
            CyclicBarrier barrier = new CyclicBarrier(2);

            el.addHandler(() -> {
                waitQuietly(barrier);

                while (!Thread.currentThread().isInterrupted()) {
                    Jvm.pause(10);
                }
                wasStoppedSuccessfully.set(true);
                return false;
            });

            waitQuietly(barrier);
            Jvm.pause(10);
            el.stop();

            TimingPauser pauser = Pauser.balanced();
            while (!wasStoppedSuccessfully.get()) {
                pauser.pause(1, TimeUnit.SECONDS);
            }
            assertTrue(wasStoppedSuccessfully.get());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    private void waitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedRuntimeException("Interrupted waiting at barrier");
        }
    }
}
