/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A second {@code stop()} waits in {@code awaitTermination()} while the first
 * {@code stop()} runs. The wait must end when its timeout passes, and the loop
 * must not stay in {@code STOPPING} after a stop callback throws.
 */
@Timeout(30)
class TerminationWaitTimeoutTest extends ThreadsTestCommon {

    private static final long SHORT_TIMEOUT_MS = 200;

    @Test
    void secondStopReturnsAfterTimeoutWhileFirstStopBlocks() throws Exception {
        final ControlledLoop loop = new ControlledLoop(SHORT_TIMEOUT_MS);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread stopper = startStopper(loop, failure);
        expectException("awaitTermination() timed out");
        final Thread waiter = daemon(loop::stop, "termination-waiter");
        try {
            waiter.start();
            waiter.join(5_000);
            assertFalse(waiter.isAlive(), "second stop() did not return after the termination timeout");
            assertTrue(stopper.isAlive());
            assertEquals(1, countExceptions("awaitTermination() timed out"),
                    "the timeout must be logged once, not once a millisecond");
        } finally {
            // A waiter that never returned would spin forever and pin the JVM.
            waiter.interrupt();
            loop.release.countDown();
            join(stopper);
            loop.close();
        }
        assertNull(failure.get());
    }

    @Test
    void secondStopReturnsWhenFirstStopThrew() throws Exception {
        final ControlledLoop loop = new ControlledLoop(SHORT_TIMEOUT_MS);
        loop.stopFailure = new IllegalStateException("stop callback failed deliberately");
        loop.start();
        assertThrows(IllegalStateException.class, loop::stop);
        final Thread waiter = daemon(loop::stop, "termination-waiter");
        try {
            waiter.start();
            waiter.join(5_000);
            assertFalse(waiter.isAlive(), "second stop() did not return after the first stop() threw");
            assertTrue(loop.isStopped());
        } finally {
            waiter.interrupt();
            loop.close();
        }
    }

    private static Thread daemon(Runnable task, String name) {
        final Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static Thread startStopper(ControlledLoop loop, AtomicReference<Throwable> failure) throws InterruptedException {
        final Thread thread = new Thread(() -> {
            try {
                loop.stop();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "termination-stopper");
        thread.start();
        assertTrue(loop.stopping.await(5, TimeUnit.SECONDS));
        return thread;
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        assertFalse(thread.isAlive(), () -> thread.getName() + " did not terminate");
    }

    private static final class ControlledLoop extends AbstractLifecycleEventLoop {
        final CountDownLatch stopping = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        RuntimeException stopFailure;

        ControlledLoop(long awaitTerminationTimeoutMs) {
            super("controlled", awaitTerminationTimeoutMs);
        }

        @Override
        protected void performStart() {
        }

        @Override
        protected void performStopFromStarted() {
            performStopFromNew();
        }

        @Override
        protected void performStopFromNew() {
            if (stopFailure != null)
                throw stopFailure;
            stopping.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "stop gate not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Override
        public boolean isRunningOnThread(Thread thread) {
            return false;
        }

        @Override
        public void addHandler(EventHandler handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unpause() {
        }

        @Override
        public boolean isAlive() {
            return stopping.getCount() == 0 && release.getCount() != 0;
        }
    }
}
