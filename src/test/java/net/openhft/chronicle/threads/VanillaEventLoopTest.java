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
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.testframework.ExecutorServiceUtil;
import net.openhft.chronicle.testframework.Waiters;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public class VanillaEventLoopTest extends ThreadsTestCommon {

    private static final String HANDLER_LOOP_STARTED_EXCEPTION_TXT = "Something went wrong in loopStarted!!!";
    private static final String HANDLER_LOOP_FINISHED_EXCEPTION_TXT = "Something went wrong in loopFinished!!!";
    private static final String HANDLER_CLOSE_EXCEPTION_TXT = "Something went wrong in close!!!";

    final AtomicInteger loopStartedCalled = new AtomicInteger();
    final AtomicInteger loopFinishedCalled = new AtomicInteger();
    final AtomicInteger closeCalled = new AtomicInteger();

    class GoodTimerHandler implements EventHandler, Closeable {
        private EventLoop eventLoop;

        @Override
        public void eventLoop(EventLoop eventLoop) {
            this.eventLoop = eventLoop;
        }

        public EventLoop eventLoop() {
            return eventLoop;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return HandlerPriority.TIMER;
        }

        @Override
        public void loopStarted() {
            loopStartedCalled.incrementAndGet();
        }

        @Override
        public boolean action() {
            return false;
        }

        @Override
        public void loopFinished() {
            loopFinishedCalled.incrementAndGet();
        }

        @Override
        public void close() throws IOException {
            closeCalled.incrementAndGet();
        }
    }

    class GoodDaemonHandler extends GoodTimerHandler {
        @Override
        public @NotNull HandlerPriority priority() {
            return HandlerPriority.DAEMON;
        }
    }

    class ThrowingTimerHandler extends GoodTimerHandler {

        @Override
        public void loopStarted() {
            super.loopStarted();
            throw new IllegalStateException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
        }

        @Override
        public void loopFinished() {
            super.loopFinished();
            throw new IllegalStateException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
        }

        @Override
        public void close() throws IOException {
            super.close();
            throw new IllegalStateException(HANDLER_CLOSE_EXCEPTION_TXT);
        }
    }

    class ThrowingDaemonHandler extends ThrowingTimerHandler {
        @Override
        public @NotNull HandlerPriority priority() {
            return HandlerPriority.DAEMON;
        }
    }

    @BeforeEach
    public void beforeEach() {
        loopStartedCalled.set(0);
        loopFinishedCalled.set(0);
        closeCalled.set(0);
    }

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

    // MediumHandler tests

    @Test
    void addingHandlerBeforeStart() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            // Add the handler.
            GoodTimerHandler handler = new GoodTimerHandler();
            eventLoop.addHandler(handler);

            // Start the loop.
            eventLoop.start();
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);
            Waiters.waitForCondition("loop started called", () -> (loopStartedCalled.get() > 0), 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(0, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventLoop.stop();
            Waiters.waitForCondition("Event loop stopped", eventLoop::isStopped, 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
        }

        // Check the handler.
        assertEquals(1, loopStartedCalled.get());
        assertEquals(1, loopFinishedCalled.get());
        assertEquals(1, closeCalled.get());
    }

    @Test
    void addingHandlerAfterStart() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            // Start the loop.
            eventLoop.start();
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);

            // Add the handler.
            GoodTimerHandler handler = new GoodTimerHandler();
            eventLoop.addHandler(handler);

            Waiters.waitForCondition("Loop started called",() -> (loopStartedCalled.get() > 0), 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(0, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventLoop.stop();
            Waiters.waitForCondition("Event loop stopped", eventLoop::isStopped, 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
        }

        // Check the handler.
        assertEquals(1, loopStartedCalled.get());
        assertEquals(1, loopFinishedCalled.get());
        assertEquals(1, closeCalled.get());
    }

    @Test
    void handlerRemovedAddingHandlerBeforeStart() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {
            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // Add handler before loop has started. loopStarted not called yet.
            ThrowingTimerHandler handler = new ThrowingTimerHandler();
            eventLoop.addHandler(handler);

            // Start the loop. loopStarted called and exception thrown. Expect handler to be removed.
            eventLoop.start();

            // Wait for loop to start and handler to be removed.
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);
            Waiters.waitForCondition("Handler should be closed", () -> (closeCalled.get() > 0), 5000);
            Waiters.waitForCondition("Handler should be removed", () -> (eventLoop.handlerCount() == 0), 5000);

            assertTrue(eventLoop.isAlive());
            assertTrue(eventLoop.newHandlers.isEmpty());

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(1, closeCalled.get());

            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount());

            // Event loop is running.
            checkEventLoopAlive(eventLoop);
        }
    }

    @Test
    void handlerRemovedAddingHandlerDuringEventLoop() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // start the event loop with no handlers.
            eventLoop.start();

            // Wait for the handler to be started.
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);

            // Add the new handler. It should be picked up by the event loop and removed after exception in loopStarted.
            ThrowingTimerHandler handler = new ThrowingTimerHandler();
            eventLoop.addHandler(handler);

            // Wait for handler to be removed.
            Waiters.waitForCondition("Handler should be closed", () -> (closeCalled.get() > 0), 5000);
            Waiters.waitForCondition("Handler should be removed", () -> (eventLoop.handlerCount() == 0), 5000);

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(1, closeCalled.get());

            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount());

            // Event loop is running.
            checkEventLoopAlive(eventLoop);
        }
    }

    // Daemon Handler test cases.

    @Test
    void addingHighHandlerBeforeStart() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            // Add the handler.
            GoodDaemonHandler handler = new GoodDaemonHandler();
            eventLoop.addHandler(handler);

            // Start the loop.
            eventLoop.start();
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);
            Waiters.waitForCondition("loop started called", () -> (loopStartedCalled.get() > 0), 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(0, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventLoop.stop();
            Waiters.waitForCondition("Event loop stopped", eventLoop::isStopped, 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
        }

        // Check the handler.
        assertEquals(1, loopStartedCalled.get());
        assertEquals(1, loopFinishedCalled.get());
        assertEquals(1, closeCalled.get());
    }

    @Test
    void addingHighHandlerAfterStart() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {


            // Start the loop.
            eventLoop.start();
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);

            // Add the handler.
            GoodDaemonHandler handler = new GoodDaemonHandler();
            eventLoop.addHandler(handler);

            Waiters.waitForCondition("Loop started called",() -> (loopStartedCalled.get() > 0), 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(0, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventLoop.stop();
            Waiters.waitForCondition("Event loop stopped", eventLoop::isStopped, 5000);

            // Check the handler.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(0, closeCalled.get());
        }

        // Check the handler.
        assertEquals(1, loopStartedCalled.get());
        assertEquals(1, loopFinishedCalled.get());
        assertEquals(1, closeCalled.get());
    }


    @Test
    void highHandlerRemovedAddingHandlerBeforeStart() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // Add handler before loop has started. loopStarted not called yet.
            ThrowingDaemonHandler handler = new ThrowingDaemonHandler();
            eventLoop.addHandler(handler);

            // Start the loop. loopStarted called and exception thrown. Expect handler to be removed.
            eventLoop.start();

            // Wait for loop to start and handler to be removed.
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);
            Waiters.waitForCondition("Handler should be closed", () -> (closeCalled.get() > 0), 5000);
            Waiters.waitForCondition("Handler should be removed", () -> (eventLoop.handlerCount() == 0), 5000);

            assertTrue(eventLoop.isAlive());
            assertTrue(eventLoop.newHandlers.isEmpty());

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(1, closeCalled.get());

            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount());

            // Event loop is running.
            checkEventLoopAlive(eventLoop);
        }
    }

    @Test
    void handlerRemovedUpdatingHighHandlerDuringEventLoop() {
        try (VanillaEventLoop eventLoop = new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null,VanillaEventLoop.ALLOWED_PRIORITIES)) {

            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // start the event loop with no handlers.
            eventLoop.start();

            // Wait for the handler to be started.
            Waiters.waitForCondition("Event loop started", eventLoop::isStarted, 5000);

            // Add the new handler. It should be picked up by the event loop and removed after exception in loopStarted.
            ThrowingDaemonHandler handler = new ThrowingDaemonHandler();
            eventLoop.addHandler(handler);

            // Wait for handler to be removed.
            Waiters.waitForCondition("Handler should be closed", () -> (closeCalled.get() > 0), 5000);
            Waiters.waitForCondition("Handler should be removed", () -> (eventLoop.handlerCount() == 0), 5000);

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, loopStartedCalled.get());
            assertEquals(1, loopFinishedCalled.get());
            assertEquals(1, closeCalled.get());

            // Handler has been removed.
            assertEquals(0, eventLoop.handlerCount());

            // Event loop is running.
            checkEventLoopAlive(eventLoop);
        }
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
