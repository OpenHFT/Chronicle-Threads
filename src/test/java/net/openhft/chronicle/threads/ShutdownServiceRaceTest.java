/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop thread exits while {@code MediumEventLoop.shutdownService()} waits
 * for it. The exit sets the volatile {@code thread} field to null between two
 * reads of that field, so the wait must not read the field again.
 */
@Timeout(60)
class ShutdownServiceRaceTest extends ThreadsTestCommon {

    /**
     * {@code Threads.shutdownDaemon} interrupts the loop thread once, through
     * {@code shutdownNow()}. The wait loop of {@code shutdownService} interrupts
     * it a second time, after its null check of the thread field. A handler
     * that exits on the second interrupt therefore exits inside the wait loop.
     */
    private static final int INTERRUPTS_BEFORE_EXIT = 2;

    @Test
    void stopSurvivesLoopThreadExitDuringShutdownWait() throws Exception {
        expectException("*** FAILED TO TERMINATE");
        expectException("THREAD DID NOT SHUTDOWN");
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch never = new CountDownLatch(1);
        final MediumEventLoop loop = new MediumEventLoop(null, "race", Pauser.balanced(), true, null);
        loop.addHandler(() -> {
            running.countDown();
            awaitInterrupts(never, INTERRUPTS_BEFORE_EXIT);
            throw InvalidEventHandlerException.reusable();
        });
        loop.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));
        try {
            assertDoesNotThrow(loop::stop, "stop() must survive the loop thread exiting during its wait");
        } finally {
            never.countDown();
            loop.close();
        }
    }

    private static void awaitInterrupts(CountDownLatch latch, int interrupts) {
        int seen = 0;
        while (seen < interrupts) {
            try {
                latch.await();
                return;
            } catch (InterruptedException e) {
                seen++;
            }
        }
        Thread.currentThread().interrupt();
    }
}
