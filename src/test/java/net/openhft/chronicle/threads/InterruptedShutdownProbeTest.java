/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptedShutdownProbeTest extends ThreadsTestCommon {
    @Test
    void interruptedShutdownStillInterruptsBlockedWorker() throws Exception {
        checkShutdown(false);
    }

    @Test
    void interruptedDaemonShutdownStillInterruptsBlockedWorker() throws Exception {
        checkShutdown(true);
    }

    private void checkShutdown(boolean daemon) throws Exception {
        ExecutorService service = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        try {
            service.submit(() -> {
                started.countDown();
                try {
                    blocked.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            if (daemon)
                Threads.shutdownDaemon(service);
            else
                Threads.shutdown(service);
            assertTrue(Thread.interrupted(), "preserve the caller's interrupt status");
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "blocked worker must receive cancellation");
        } finally {
            Thread.interrupted();
            service.shutdownNow();
            assertTrue(service.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
