/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HandlerAdderFailureTest {
    @Test
    void reportsSupplierExceptionWithoutHanging() throws Exception {
        checkFailure(new IllegalStateException("supplier failed"));
    }

    @Test
    void reportsSupplierErrorWithoutHanging() throws Exception {
        checkFailure(new AssertionError("supplier failed"));
    }

    @Test
    void stopPreservesInterruption() {
        EventLoopConcurrencyStressTest.HandlerAdder adder = new EventLoopConcurrencyStressTest.HandlerAdder(
                null, new CyclicBarrier(1), () -> null);
        Thread.currentThread().interrupt();
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, adder::stopAddingHandlers);
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static void checkFailure(Throwable original) throws Exception {
        EventLoopConcurrencyStressTest.HandlerAdder adder = new EventLoopConcurrencyStressTest.HandlerAdder(
                null, new CyclicBarrier(1), () -> {
                    if (original instanceof Error)
                        throw (Error) original;
                    throw (RuntimeException) original;
                });
        Thread worker = new Thread(adder, "failing-handler-adder");
        worker.setUncaughtExceptionHandler((t, e) -> { });
        worker.start();
        worker.join(2000);
        assertFalse(worker.isAlive());
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            try {
                adder.stopAddingHandlers();
            } catch (Throwable failure) {
                reported.set(failure);
            } finally {
                completed.countDown();
            }
        }, "handler-adder-stop");
        stopper.setDaemon(true);
        try {
            stopper.start();
            assertTrue(completed.await(2, TimeUnit.SECONDS), "A failed worker must release its waiter");
            assertTrue(reported.get() instanceof AssertionError);
            assertSame(original, reported.get().getCause());
        } finally {
            stopper.interrupt();
            stopper.join(2000);
            assertFalse(stopper.isAlive());
        }
    }
}
