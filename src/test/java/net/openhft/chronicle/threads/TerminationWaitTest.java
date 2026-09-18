/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.threads.EventHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TerminationWaitTest extends ThreadsTestCommon {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalAndRepeatedStop(boolean started) {
        try (ControlledLoop loop = new ControlledLoop()) {
            if (started)
                loop.start();
            loop.release.countDown();
            loop.stop();
            loop.stop();
            assertEquals(1, loop.stopCalls.get());
            assertEquals(started, loop.stoppedFromStarted);
        }
    }

    @Test
    void concurrentStopCompletesBeforeDeadline() throws Exception {
        ControlledLoop loop = new ControlledLoop();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread stopper = startStopper(loop, failure);
        Thread waiter = new Thread(() -> {
            try {
                loop.stop();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "termination-waiter");
        try {
            waiter.start();
            loop.release.countDown();
            join(waiter);
            join(stopper);
            assertNull(failure.get());
            assertEquals(1, loop.stopCalls.get());
        } finally {
            loop.release.countDown();
            join(stopper);
            join(waiter);
            loop.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutDoesNotCompleteStopOrClose(boolean close) throws Exception {
        ControlledLoop loop = new ControlledLoop();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread stopper = startStopper(loop, workerFailure);
        expectException("awaitTermination() timed out");
        try {
            loop.clockStep.set(100);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> { if (close) loop.close(); else loop.stop(); });
            assertTrue(failure.getMessage().contains("loop=controlled"));
            assertTrue(failure.getMessage().contains("lifecycle=STOPPING"));
            assertTrue(failure.getMessage().contains("stopper=termination-stopper"));
            assertTrue(failure.getMessage().contains("CountDownLatch.await"));
            assertFalse(loop.isClosed());
            assertEquals(0, loop.resourcesClosed.get());
            assertTrue(stopper.isAlive());
            assertThrows(IllegalStateException.class, loop::close);
            Map<ExceptionKey, Integer> exceptions = Jvm.getValue(this, "exceptions");
            int diagnostics = exceptions.entrySet().stream()
                    .filter(e -> e.getKey().message.contains("awaitTermination() timed out"))
                    .mapToInt(Map.Entry::getValue).sum();
            assertEquals(1, diagnostics, "Repeated waits must not flood the log");
        } finally {
            loop.release.countDown();
            join(stopper);
            loop.close();
        }
        assertNull(workerFailure.get());
        assertTrue(loop.isClosed());
        assertEquals(1, loop.resourcesClosed.get());
    }

    @Test
    void interruptionPreservesStatusAndResourceOwnership() throws Exception {
        ControlledLoop loop = new ControlledLoop();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread stopper = startStopper(loop, workerFailure);
        expectException("awaitTermination() interrupted");
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, loop::close);
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(loop.isClosed());
            assertEquals(0, loop.resourcesClosed.get());
        } finally {
            Thread.interrupted();
            loop.release.countDown();
            join(stopper);
            loop.close();
        }
        assertNull(workerFailure.get());
    }

    @Test
    void failedStopRemainsVisibleThroughClose() {
        ControlledLoop loop = new ControlledLoop();
        IllegalArgumentException original = new IllegalArgumentException("stop callback failed deliberately");
        loop.stopFailure = original;
        expectException("awaitTermination() stop callback failed");
        try {
            assertSame(original, assertThrows(IllegalArgumentException.class, loop::close));
            assertFalse(loop.isClosed());
            IllegalStateException repeated = assertThrows(IllegalStateException.class, loop::close);
            assertSame(original, repeated.getCause());
            assertTrue(repeated.getMessage().contains("lifecycle=STOPPING"));
            assertEquals(0, loop.resourcesClosed.get());
        } finally {
            // This deliberately failed fake owns no native resources or worker threads.
            loop.unmonitor();
        }
    }

    private static Thread startStopper(ControlledLoop loop, AtomicReference<Throwable> failure) throws Exception {
        Thread thread = new Thread(() -> {
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
        thread.join(5000);
        assertFalse(thread.isAlive(), () -> thread.getName() + " did not terminate");
    }

    private static final class ControlledLoop extends AbstractLifecycleEventLoop {
        final AtomicLong clockStep;
        final CountDownLatch stopping = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger stopCalls = new AtomicInteger();
        final AtomicInteger resourcesClosed = new AtomicInteger();
        boolean stoppedFromStarted;
        RuntimeException stopFailure;

        ControlledLoop() {
            this(new AtomicLong(), new AtomicLong());
        }

        private ControlledLoop(AtomicLong clock, AtomicLong step) {
            super("controlled", 100, () -> clock.getAndAdd(step.get()));
            this.clockStep = step;
        }

        @Override protected void performStart() { }
        @Override protected void performStopFromStarted() { stoppedFromStarted = true; performStopFromNew(); }
        @Override protected void performStopFromNew() {
            stopCalls.incrementAndGet();
            if (stopFailure != null)
                throw stopFailure;
            stopping.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "Stop gate not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        @Override protected void performClose() { super.performClose(); resourcesClosed.incrementAndGet(); }
        @Override public boolean isRunningOnThread(Thread thread) { return false; }
        @Override public void addHandler(EventHandler handler) { throw new UnsupportedOperationException(); }
        @Override public void unpause() { }
        @Override public boolean isAlive() { return stopping.getCount() == 0 && release.getCount() != 0; }
    }
}
