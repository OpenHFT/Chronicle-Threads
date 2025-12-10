/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.testframework.ExecutorServiceUtil;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static net.openhft.chronicle.threads.TestEventHandlers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared event loop behaviour tests for the concrete implementations.
 */
abstract class AbstractEventLoopTest extends ThreadsTestCommon {

    protected abstract Supplier<? extends MediumEventLoop> eventLoopSupplier();

    protected abstract Supplier<? extends MediumEventLoop> concurrentLoopSupplier();

    protected abstract HandlerPriority firstPriority();

    protected abstract HandlerPriority secondPriority();

    @Test
    void testAddingTwoEventHandlersBeforeStartingLoopIsThreadSafe() {
        for (int i = 0; i < 10_000; i++) {
            try (MediumEventLoop eventLoop = eventLoopSupplier().get()) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                IntStream.range(0, 2).parallel()
                        .forEach(ignored -> {
                            try {
                                EventHandler handler = new NoOpHandler();
                                barrier.await();
                                eventLoop.addHandler(handler);
                            } catch (InterruptedException | BrokenBarrierException e) {
                                throw new RuntimeException(e);
                            }
                        });
                assertEquals(2, eventLoop.handlerCount());
            }
        }
    }

    @Test
    void testAddingTwoEventHandlersWithBlockedMainLoopDoesNotHang() {
        for (int i = 0; i < 10_000; i++) {
            try (MediumEventLoop eventLoop = eventLoopSupplier().get()) {
                eventLoop.start();
                CyclicBarrier barrier = new CyclicBarrier(3);
                eventLoop.addHandler(() -> {
                    try {
                        barrier.await();
                        return false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InvalidEventHandlerException();
                    } catch (BrokenBarrierException e) {
                        throw new InvalidEventHandlerException();
                    }
                });
                IntStream.range(0, 2).parallel()
                        .forEach(ignored -> {
                            try {
                                EventHandler handler = new NoOpHandler();
                                eventLoop.addHandler(handler);
                                barrier.await();
                            } catch (InterruptedException | BrokenBarrierException e) {
                                throw new RuntimeException(e);
                            }
                        });

                Waiters.waitForCondition("Not all handlers arrived in the loop",
                        () -> eventLoop.handlerCount() == 3, 1000);
            }
        }
        assertTrue(true); // If we reach here, the test passed
    }

    @Test
    void addingFirstPriorityHandlerBeforeStart() {
        addingHandlerBeforeStart(new CountingHandler(firstPriority()));
    }

    @Test
    void addingSecondPriorityHandlerBeforeStart() {
        addingHandlerBeforeStart(new CountingHandler(secondPriority()));
    }

    @Test
    void addingFirstPriorityHandlerAfterStart() {
        addingHandlerAfterStart(new CountingHandler(firstPriority()));
    }

    @Test
    void addingSecondPriorityHandlerAfterStart() {
        addingHandlerAfterStart(new CountingHandler(secondPriority()));
    }

    @Test
    void throwingFirstPriorityHandlerAddedBeforeStart() {
        throwingHandlerAddedBeforeStart(new ThrowingHandler(firstPriority(), false, false));
    }

    @Test
    void throwingSecondPriorityHandlerAddedBeforeStart() {
        throwingHandlerAddedBeforeStart(new ThrowingHandler(secondPriority(), false, false));
    }

    @Test
    void testThrowingFirstPriorityHandlerAddedAfterStart() {
        throwingHandlerAddingAfterStart(new ThrowingHandler(firstPriority(), false, false));
    }

    @Test
    void testThrowingSecondPriorityHandlerAddedAfterStart() {
        throwingHandlerAddingAfterStart(new ThrowingHandler(secondPriority(), false, false));
    }

    @Test
    void concurrentStartStopDoesNoThrowError() throws ExecutionException, InterruptedException {
        ExecutorService es = Executors.newCachedThreadPool();
        for (int i = 0; i < 100; i++) {
            try (final MediumEventLoop mediumEventLoop = concurrentLoopSupplier().get()) {
                final Future<?> starter = es.submit(mediumEventLoop::start);
                final Future<?> stopper = es.submit(mediumEventLoop::stop);
                starter.get();
                stopper.get();
            }
        }
        ExecutorServiceUtil.shutdownAndWaitForTermination(es);
    }

    private void addingHandlerBeforeStart(CountingHandler handler) {
        runHandlerLifecycle(handler, true);
    }

    private void addingHandlerAfterStart(CountingHandler handler) {
        runHandlerLifecycle(handler, false);
    }

    private void runHandlerLifecycle(CountingHandler handler, boolean addBeforeStart) {
        try (MediumEventLoop eventLoop = eventLoopSupplier().get()) {

            if (addBeforeStart) {
                eventLoop.addHandler(handler);
            }

            // Start the loop.
            eventLoop.start();
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);

            if (!addBeforeStart) {
                eventLoop.addHandler(handler);
            }

            Waiters.waitForCondition("Loop started called", () -> (handler.loopStartedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(0, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventLoop.stop();
            Waiters.waitForCondition("Event loop stopped", eventLoop::isStopped, 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(1, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
        }

        // Check the handler.
        assertEquals(1, handler.loopStartedCalled());
        assertEquals(1, handler.loopFinishedCalled());
        assertEquals(1, handler.closeCalled());
    }

    private void throwingHandlerAddedBeforeStart(ThrowingHandler handler) {

        try (MediumEventLoop eventLoop = eventLoopSupplier().get()) {
            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // Add handler before loop has started. loopStarted not called yet.
            eventLoop.addHandler(handler);

            // Start the loop. loopStarted called and exception thrown. Expect handler to be removed.
            eventLoop.start();

            // Wait for loop to start and handler to be removed.
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);
            Waiters.waitForCondition("Handler should be closed", () -> (handler.closeCalled() > 0), 5000);
            Waiters.waitForCondition("Handler should be removed", () -> (eventLoop.handlerCount() == 0), 5000);

            assertTrue(eventLoop.isAlive());
            assertTrue(eventLoop.newHandlers.isEmpty());

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(1, handler.loopFinishedCalled());
            assertEquals(1, handler.closeCalled());
            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount());

            // Event loop is running.
            checkEventLoopAlive(eventLoop);
        }
    }

    private void throwingHandlerAddingAfterStart(ThrowingHandler handler) {
        try (MediumEventLoop eventLoop = eventLoopSupplier().get()) {
            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // start the event loop with no handlers.
            eventLoop.start();

            // Wait for the handler to be started.
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);

            // Add the new handler. It should be picked up by the event loop and removed after exception in loopStarted.
            eventLoop.addHandler(handler);

            // Wait for handler to be removed.
            Waiters.waitForCondition("Handler should be closed", () -> (handler.closeCalled() > 0), 5000);
            Waiters.waitForCondition("Handler should be removed", () -> (eventLoop.handlerCount() == 0), 5000);

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(1, handler.loopFinishedCalled());
            assertEquals(1, handler.closeCalled());

            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount());

            // Event loop is running.
            checkEventLoopAlive(eventLoop);
        }
    }

    private void checkEventLoopAlive(MediumEventLoop eventLoop) {
        // Expect the eventLoop to continue.
        assertTrue(eventLoop.isStarted());
        assertTrue(eventLoop.isAlive());
        assertFalse(eventLoop.isStopped());
        assertFalse(eventLoop.isClosing());
        assertFalse(eventLoop.isClosed());
        assertTrue(Objects.requireNonNull(eventLoop.thread()).isAlive());
    }

    private static class NoOpHandler implements EventHandler {

        @Override
        public boolean action() {
            return false;
        }
    }
}
