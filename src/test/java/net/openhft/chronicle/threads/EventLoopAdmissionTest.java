/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.testframework.ExecutorServiceUtil;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static net.openhft.chronicle.threads.TestEventHandlers.CountingHandler;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class EventLoopAdmissionTest extends ThreadsTestCommon {
    enum LoopType {
        MEDIUM, VANILLA;

        MediumEventLoop create() {
            return this == MEDIUM
                    ? new MediumEventLoop(null, "admission", Pauser.balanced(), true, null)
                    : new VanillaEventLoop(null, "admission", Pauser.balanced(), 10, true, null,
                    VanillaEventLoop.ALLOWED_PRIORITIES);
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void rejectsRegistrationAfterStopWithoutStart(LoopType type) throws Exception {
        assertRejectedAfterStop(type, false);
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void rejectsRegistrationAfterStartedLoopStops(LoopType type) throws Exception {
        assertRejectedAfterStop(type, true);
    }

    @SuppressWarnings("try") // The assertion observes the completed close before scope cleanup.
    private void assertRejectedAfterStop(LoopType type, boolean start) throws Exception {
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
        try (MediumEventLoop loop = type.create()) {
            if (start) {
                loop.start();
                Waiters.waitForCondition("Loop did not start", loop::isAlive, 5_000);
            }
            loop.stop();
            assertTrue(loop.isStopped());
            assertThrows(HandlerRegistrationClosedException.class, () -> loop.addHandler(handler));
            loop.close();
            assertAll(
                    () -> assertEquals(0, handler.loopStartedCalled()),
                    () -> assertEquals(0, handler.loopFinishedCalled()),
                    () -> assertEquals(0, handler.closeCalled()));
        } finally {
            if (handler.closeCalled() == 0)
                handler.close();
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    @SuppressWarnings("try")
    void finishesAcceptedHandlerWhenLoopNeverStarts(LoopType type) {
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
        try (MediumEventLoop loop = type.create()) {
            loop.addHandler(handler);
            loop.stop();
            assertEquals(0, handler.loopStartedCalled());
            assertEquals(1, handler.loopFinishedCalled());
            loop.close();
        }
        assertEquals(1, handler.loopFinishedCalled());
        assertEquals(1, handler.closeCalled());
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    @SuppressWarnings("try")
    void pendingHandlerFinishesOnceAndLateRegistrationIsRejected(LoopType type) throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        CountingHandler pending = new CountingHandler(HandlerPriority.MEDIUM);
        CountingHandler rejected = new CountingHandler(HandlerPriority.MEDIUM);
        try (MediumEventLoop loop = type.create()) {
            loop.addHandler(new CountingHandler(HandlerPriority.MEDIUM) {
                @Override
                public boolean action() {
                    running.countDown();
                    awaitUninterruptibly(release);
                    return false;
                }
            });
            loop.start();
            try {
                assertTrue(running.await(5, TimeUnit.SECONDS));
                loop.addHandler(pending);
                assertTrue(loop.newHandlers.contains(pending));
                Future<?> stopped = stopper.submit(loop::stop);
                Waiters.waitForCondition("Stop did not begin", loop::isStopped, 5_000);
                assertThrows(HandlerRegistrationClosedException.class, () -> loop.addHandler(rejected));
                release.countDown();
                stopped.get(5, TimeUnit.SECONDS);
                // The last iteration may initialise the queued handler after the
                // stop request, but it must not run its action.
                assertEquals(0, pending.actionCalled());
                assertEquals(1, pending.loopFinishedCalled());
                loop.close();
                assertEquals(1, pending.closeCalled());
                assertEquals(0, rejected.loopFinishedCalled());
                assertEquals(0, rejected.closeCalled());
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            ExecutorServiceUtil.shutdownAndWaitForTermination(stopper);
            if (rejected.closeCalled() == 0)
                rejected.close();
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void finishCallbackCanWaitForAnotherThreadsRejectedRegistration(LoopType type) {
        ExecutorService registrar = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountingHandler rejected = new CountingHandler(HandlerPriority.MEDIUM);
        try (MediumEventLoop loop = type.create()) {
            loop.addHandler(new CountingHandler(HandlerPriority.MEDIUM) {
                @Override
                public void loopFinished() {
                    super.loopFinished();
                    try {
                        getUninterruptibly(registrar.submit(() -> assertThrows(HandlerRegistrationClosedException.class,
                                () -> loop.addHandler(rejected))));
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                }
            });
            loop.start();
            Waiters.waitForCondition("Loop did not start", loop::isAlive, 5_000);
            loop.stop();
            assertNull(failure.get(), () -> "Finish callback failed: " + failure.get());
            assertEquals(0, rejected.loopFinishedCalled());
        } finally {
            ExecutorServiceUtil.shutdownAndWaitForTermination(registrar);
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void finishesAcceptedHandlersWhenSubmittedLoopTaskIsCancelled(LoopType type) throws Exception {
        assertCancelledStartupIsFinished(type, false);
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void privateGroupFinishesCancelledStartupBeforeExecutorTerminates(LoopType type) throws Exception {
        assertCancelledStartupIsFinished(type, true);
    }

    private void assertCancelledStartupIsFinished(LoopType type, boolean privateGroup) throws Exception {
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
        try (MediumEventLoop loop = type.create()) {
            loop.privateGroup(privateGroup);
            Future<?> occupation = loop.service.submit(() -> {
                occupied.countDown();
                if (privateGroup) {
                    awaitUninterruptibly(release);
                    return;
                }
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertTrue(occupied.await(5, TimeUnit.SECONDS));
                loop.addHandler(handler);
                loop.start();
                loop.stop();
                assertEquals(0, handler.loopStartedCalled());
                assertEquals(1, handler.loopFinishedCalled());
            } finally {
                release.countDown();
                occupation.get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, handler.closeCalled());
        assertEquals(1, handler.loopFinishedCalled());
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    @SuppressWarnings("try")
    void racingRegistrationAndCloseHasOneOwner(LoopType type) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
                try (MediumEventLoop loop = type.create()) {
                    loop.start();
                    Waiters.waitForCondition("Loop did not start", loop::isAlive, 5_000);
                    CyclicBarrier start = new CyclicBarrier(3);
                    Future<Boolean> accepted = workers.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            loop.addHandler(handler);
                            return true;
                        } catch (IllegalStateException rejection) {
                            return false;
                        }
                    });
                    Future<?> closed = workers.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        loop.close();
                        return null;
                    });
                    start.await(5, TimeUnit.SECONDS);
                    boolean ownedByLoop = accepted.get(5, TimeUnit.SECONDS);
                    closed.get(5, TimeUnit.SECONDS);
                    assertEquals(ownedByLoop ? 1 : 0, handler.loopFinishedCalled(), "Finish ownership, attempt " + attempt);
                    assertEquals(ownedByLoop ? 1 : 0, handler.closeCalled(), "Close ownership, attempt " + attempt);
                } finally {
                    if (handler.closeCalled() == 0)
                        handler.close();
                }
            }
        } finally {
            ExecutorServiceUtil.shutdownAndWaitForTermination(workers);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    private static <T> T getUninterruptibly(Future<T> future) throws ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }
}
