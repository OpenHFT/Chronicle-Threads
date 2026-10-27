/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StopAdmissionTest extends ThreadsTestCommon {
    @Test
    void stoppedUnstartedLoopRejectsWithoutTakingOwnership() {
        try (MediumEventLoop loop = new MediumEventLoop(null, "stopped-new", Pauser.balanced(), true, null);
             OwnedHandler rejected = new OwnedHandler(HandlerPriority.MEDIUM)) {
            loop.stop();
            assertThrows(RejectedExecutionException.class, () -> loop.addHandler(rejected));
            assertEquals(0, rejected.closed.get());
            assertEquals(0, rejected.started.get());
        }
    }

    @Test
    void stoppingRunningLoopRejectsWithoutTakingOwnership() throws Exception {
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try (MediumEventLoop loop = new MediumEventLoop(null, "stopping-medium", Pauser.balanced(), true, null);
             OwnedHandler rejected = new OwnedHandler(HandlerPriority.MEDIUM)) {
            loop.addHandler(blockingHandler(executing, release));
            loop.start();
            assertTrue(executing.await(5, TimeUnit.SECONDS));
            Future<?> stopping = stopper.submit(loop::stop);
            try {
                Waiters.waitForCondition("STOPPING", loop::isStopped, 5000);
                assertThrows(RejectedExecutionException.class, () -> loop.addHandler(rejected));
                assertEquals(0, rejected.closed.get());
                assertEquals(0, rejected.started.get());
            } finally {
                release.countDown();
                stopping.get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            stopper.shutdownNow();
            assertTrue(stopper.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppingGroupRejectsLazyReplicationWithoutCreatingChild() throws Exception {
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try (EventGroup group = EventGroup.builder().withName("reject-child")
                .withPriorities(HandlerPriority.MEDIUM, HandlerPriority.REPLICATION).build();
             OwnedHandler rejected = new OwnedHandler(HandlerPriority.REPLICATION)) {
            group.addHandler(blockingHandler(executing, release));
            group.start();
            assertTrue(executing.await(5, TimeUnit.SECONDS));
            Future<?> stopping = stopper.submit(group::stop);
            try {
                Waiters.waitForCondition("STOPPING", group::isStopped, 5000);
                assertThrows(RejectedExecutionException.class, () -> group.addHandler(rejected));
                assertNull(Jvm.getValue(group, "replication"));
                assertEquals(0, rejected.closed.get());
            } finally {
                release.countDown();
                stopping.get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            stopper.shutdownNow();
            assertTrue(stopper.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void creationAdmittedBeforeStopIsIncludedInStopSet() throws Exception {
        CountDownLatch coreEntered = new CountDownLatch(1);
        CountDownLatch releaseCore = new CountDownLatch(1);
        CountDownLatch creationEntered = new CountDownLatch(1);
        CountDownLatch finishCreation = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        OwnedHandler accepted = new OwnedHandler(HandlerPriority.CONCURRENT);
        try (EventGroup group = EventGroup.builder().withName("admitted-child")
                .withPriorities(HandlerPriority.MEDIUM, HandlerPriority.CONCURRENT)
                .withConcurrentThreadsNum(1).withConcurrentPauserSupplier(() -> {
                    creationEntered.countDown();
                    awaitUninterruptibly(finishCreation);
                    return Pauser.balanced();
                }).build()) {
            group.addHandler(blockingHandler(coreEntered, releaseCore));
            group.start();
            assertTrue(coreEntered.await(5, TimeUnit.SECONDS));
            Future<?> adding = callers.submit(() -> group.addHandler(accepted));
            assertTrue(creationEntered.await(5, TimeUnit.SECONDS));
            Future<?> stopping = callers.submit(group::stop);
            try {
                Waiters.waitForCondition("STOPPING", group::isStopped, 5000);
                finishCreation.countDown();
                adding.get(5, TimeUnit.SECONDS);
                releaseCore.countDown();
                stopping.get(5, TimeUnit.SECONDS);
                List<VanillaEventLoop> children = Jvm.getValue(group, "concThreads");
                assertNotNull(children.get(0), "This control must create the child");
                assertTrue(children.get(0).isStopped(), "Stop must include the admitted child");
                assertFalse(children.get(0).isAlive(), "No child may escape the completed stop");
            } finally {
                finishCreation.countDown();
                releaseCore.countDown();
            }
        } finally {
            finishCreation.countDown();
            releaseCore.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            accepted.close();
        }
        assertEquals(1, accepted.closed.get());
    }

    @Test
    void acceptedHandlerRetainsNormalLifecycle() throws Exception {
        OwnedHandler accepted = new OwnedHandler(HandlerPriority.MEDIUM);
        try (MediumEventLoop loop = new MediumEventLoop(null, "accepted", Pauser.balanced(), true, null)) {
            loop.addHandler(accepted);
            loop.start();
            Waiters.waitForCondition("Handler started", () -> accepted.started.get() == 1, 5000);
            loop.stop();
            assertEquals(1, accepted.finished.get());
            assertEquals(0, accepted.closed.get());
        }
        assertEquals(1, accepted.closed.get());
    }

    private static EventHandler blockingHandler(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            awaitUninterruptibly(release);
            return false;
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (latch.getCount() != 0) {
                try {
                    assertTrue(latch.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS), "Test latch deadline");
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    private static final class OwnedHandler extends AbstractCloseable implements EventHandler {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        private final HandlerPriority priority;

        OwnedHandler(HandlerPriority priority) {
            this.priority = priority;
            singleThreadedCheckDisabled(true);
        }

        @Override
        public HandlerPriority priority() {
            return priority;
        }

        @Override
        public boolean action() {
            return false;
        }

        @Override
        public void loopStarted() {
            started.incrementAndGet();
        }

        @Override
        public void loopFinished() {
            finished.incrementAndGet();
        }

        @Override
        protected void performClose() {
            closed.incrementAndGet();
        }
    }
}
