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

import static net.openhft.chronicle.threads.EventHandlerFixtures.*;
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
                assertEquals(2, eventLoop.handlerCount(), "both handlers added before start");
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
                assertEquals(3, eventLoop.handlerCount(), "all handlers registered");
            }
        }
    }

    @Test
    void addingFirstPriorityHandlerBeforeStart() {
        CountingHandler handler = new CountingHandler(firstPriority());
        addingHandlerBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "handler closed (priority=" + handler.priority + ")");
    }

    @Test
    void addingSecondPriorityHandlerBeforeStart() {
        CountingHandler handler = new CountingHandler(secondPriority());
        addingHandlerBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "handler closed (priority=" + handler.priority + ")");
    }

    @Test
    void addingFirstPriorityHandlerAfterStart() {
        CountingHandler handler = new CountingHandler(firstPriority());
        addingHandlerAfterStart(handler);
        assertEquals(1, handler.closeCalled(), "handler closed (priority=" + handler.priority + ")");
    }

    @Test
    void addingSecondPriorityHandlerAfterStart() {
        CountingHandler handler = new CountingHandler(secondPriority());
        addingHandlerAfterStart(handler);
        assertEquals(1, handler.closeCalled(), "handler closed (priority=" + handler.priority + ")");
    }

    @Test
    void throwingFirstPriorityHandlerAddedBeforeStart() {
        ThrowingHandler handler = new ThrowingHandler(firstPriority(), false, false);
        throwingHandlerAddedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "throwing handler closed (priority=" + handler.priority + ")");
    }

    @Test
    void throwingSecondPriorityHandlerAddedBeforeStart() {
        ThrowingHandler handler = new ThrowingHandler(secondPriority(), false, false);
        throwingHandlerAddedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "throwing handler closed (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingFirstPriorityHandlerAddedAfterStart() {
        ThrowingHandler handler = new ThrowingHandler(firstPriority(), false, false);
        throwingHandlerAddingAfterStart(handler);
        assertEquals(1, handler.closeCalled(), "throwing handler closed (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingSecondPriorityHandlerAddedAfterStart() {
        ThrowingHandler handler = new ThrowingHandler(secondPriority(), false, false);
        throwingHandlerAddingAfterStart(handler);
        assertEquals(1, handler.closeCalled(), "throwing handler closed (priority=" + handler.priority + ")");
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
        assertTrue(es.isShutdown(), "executor shut down");
        assertTrue(es.isTerminated(), "executor terminated");
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
            assertEquals(1, handler.loopStartedCalled(), "loopStarted called (priority=" + handler.priority + ")");
            assertEquals(0, handler.loopFinishedCalled(), "loopFinished not yet called (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "handler not yet closed (priority=" + handler.priority + ")");
            assertNotNull(handler.eventLoop(), "eventLoop assigned (priority=" + handler.priority + ")");

            // Stop the loop.
            eventLoop.stop();
            Waiters.waitForCondition("Event loop stopped", eventLoop::isStopped, 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled(), "loopStarted called once (priority=" + handler.priority + ")");
            assertEquals(1, handler.loopFinishedCalled(), "loopFinished called once (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "close not yet called (priority=" + handler.priority + ")");
        }

        // Check the handler.
        assertEquals(1, handler.loopStartedCalled(), "loopStarted called once (priority=" + handler.priority + ")");
        assertEquals(1, handler.loopFinishedCalled(), "loopFinished called once (priority=" + handler.priority + ")");
        assertEquals(1, handler.closeCalled(), "close called once (priority=" + handler.priority + ")");
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

            assertTrue(eventLoop.isAlive(), "eventLoop alive after handler exception");
            assertTrue(eventLoop.newHandlers.isEmpty(), "no new handlers queued after handler removal");

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, handler.loopStartedCalled(), "loopStarted called once (priority=" + handler.priority + ")");
            assertEquals(1, handler.loopFinishedCalled(), "loopFinished called once (priority=" + handler.priority + ")");
            assertEquals(1, handler.closeCalled(), "close called once (priority=" + handler.priority + ")");
            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount(), "throwing handler removed");

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
            assertEquals(1, handler.loopStartedCalled(), "loopStarted called once (priority=" + handler.priority + ")");
            assertEquals(1, handler.loopFinishedCalled(), "loopFinished called once (priority=" + handler.priority + ")");
            assertEquals(1, handler.closeCalled(), "close called once (priority=" + handler.priority + ")");

            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount(), "throwing handler removed");

            // Event loop is running.
            checkEventLoopAlive(eventLoop);
        }
    }

    private void checkEventLoopAlive(MediumEventLoop eventLoop) {
        // Expect the eventLoop to continue.
        assertTrue(eventLoop.isStarted(), "eventLoop started");
        assertTrue(eventLoop.isAlive(), "eventLoop alive");
        assertFalse(eventLoop.isStopped(), "eventLoop not stopped");
        assertFalse(eventLoop.isClosing(), "eventLoop not closing");
        assertFalse(eventLoop.isClosed(), "eventLoop not closed");
        assertTrue(Objects.requireNonNull(eventLoop.thread()).isAlive(), "eventLoop thread alive");
    }

    private static class NoOpHandler implements EventHandler {

        @Override
        public boolean action() {
            return false;
        }
    }
}
