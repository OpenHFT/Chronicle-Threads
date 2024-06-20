/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.io.InvalidMarshallableException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.testframework.ExecutorServiceUtil;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static net.openhft.chronicle.threads.TestEventHandlers.*;
import static org.junit.jupiter.api.Assertions.*;

public class VanillaEventLoopTest extends ThreadsTestCommon {

    @Test
    public void testAddingTwoEventHandlersBeforeStartingLoopIsThreadSafe() {
        for (int i = 0; i < 10_000; i++) {
            try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true,"", VanillaEventLoop.ALLOWED_PRIORITIES)) {
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
                assertEquals(2, eventLoop.mediumHandlersArray.length);
            }
        }
    }

    @Test
    public void testAddingTwoEventHandlersWithBlockedMainLoopDoesNotHang() {
        for (int i = 0; i < 10_000; i++) {
            try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null, VanillaEventLoop.ALLOWED_PRIORITIES)) {
                eventLoop.start();
                CyclicBarrier barrier = new CyclicBarrier(3);
                eventLoop.addHandler(new EventHandler() {
                    @Override
                    public boolean action() throws InvalidEventHandlerException, InvalidMarshallableException {
                        try {
                            barrier.await();
                            return false;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InvalidEventHandlerException();
                        } catch (BrokenBarrierException e) {
                            throw new InvalidEventHandlerException();
                        }
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
                        () -> eventLoop.mediumHandlersArray.length == 3, 1000);
            }
        }
    }

    void addingHandlerBeforeStart(CountingHandler handler) {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            // Add the handler.
            eventLoop.addHandler(handler);

            // Start the loop.
            eventLoop.start();
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);
            Waiters.waitForCondition("loop started called", () -> (handler.loopStartedCalled() > 0), 5000);

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

    @Test
    void addingTimerHandlerBeforeStart() {
        addingHandlerBeforeStart(new CountingHandler(HandlerPriority.TIMER));
    }

    @Test
    void addingDaemonHandlerBeforeStart() {
        addingHandlerBeforeStart(new CountingHandler(HandlerPriority.DAEMON));
    }

    void addingHandlerAfterStart(CountingHandler handler) {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            // Start the loop.
            eventLoop.start();
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);

            // Add the handler.
            eventLoop.addHandler(handler);

            Waiters.waitForCondition("Loop started called",() -> (handler.loopStartedCalled() > 0), 5000);

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

    @Test
    void addingTimerHandlerAfterStart() {
        addingHandlerAfterStart(new CountingHandler(HandlerPriority.TIMER));
    }

    @Test
    void addingDaemonHandlerAfterStart() {
        addingHandlerAfterStart(new CountingHandler(HandlerPriority.DAEMON));
    }

    void throwingHandlerAddedBeforeStart(ThrowingHandler handler) {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {
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

    @Test
    void throwingTimerHandlerAddedBeforeStart() {
        throwingHandlerAddedBeforeStart(new ThrowingHandler(HandlerPriority.TIMER, false, false));
    }

    @Test
    void throwingDaemonHandlerAddedBeforeStart() {
        throwingHandlerAddedBeforeStart(new ThrowingHandler(HandlerPriority.DAEMON, false, false));
    }

    void throwingHandlerAddingAfterStart(ThrowingHandler handler) {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {
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

    @Test
    void testThrowingTimerHandlerAddedAfterStart() {
        throwingHandlerAddingAfterStart(new ThrowingHandler(HandlerPriority.TIMER, false, false));
    }

    @Test
    void testThrowingDaemonHandlerAddedAfterStart() {
        throwingHandlerAddingAfterStart(new ThrowingHandler(HandlerPriority.DAEMON, false, false));
    }

    private void checkEventLoopAlive(VanillaEventLoop eventLoop) {
        // Expect the eventLoop to continue.
        assertTrue(eventLoop.isStarted());
        assertTrue(eventLoop.isAlive());
        assertFalse(eventLoop.isStopped());
        assertFalse(eventLoop.isClosing());
        assertFalse(eventLoop.isClosed());
        assertTrue(Objects.requireNonNull(eventLoop.thread()).isAlive());
    }

    @Test
    void concurrentStartStopDoesNoThrowError() throws ExecutionException, InterruptedException {
        ExecutorService es = Executors.newCachedThreadPool();
        for (int i = 0; i < 100; i++) {
            try (VanillaEventLoop vanillaEventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {
                final Future<?> starter = es.submit(vanillaEventLoop::start);
                final Future<?> stopper = es.submit(vanillaEventLoop::stop);
                starter.get();
                stopper.get();
            }
        }
        ExecutorServiceUtil.shutdownAndWaitForTermination(es);
    }

    private static class NoOpHandler implements EventHandler {

        @Override
        public boolean action() {
            return false;
        }
    }
}
