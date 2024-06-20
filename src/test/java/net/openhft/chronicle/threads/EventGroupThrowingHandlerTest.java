/*
 * Copyright 2016-2020 chronicle.software
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

import net.openhft.chronicle.core.threads.*;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.openhft.chronicle.threads.MonitorEventLoop.MONITOR_INITIAL_DELAY;
import static net.openhft.chronicle.threads.TestEventHandlers.*;
import static org.junit.jupiter.api.Assertions.*;

public class EventGroupThrowingHandlerTest extends ThreadsTestCommon {

    @BeforeEach
    public void handlersInit() {
        // Initial delay defaults to 10secs. Set to 10ms for testing.
        System.setProperty(MONITOR_INITIAL_DELAY, "10");
    }

    private final String EVENT_GROUP_NAME = "test";

    private EventGroup createEventGroup() {
        return EventGroup.builder().withName(EVENT_GROUP_NAME).withDaemon(true).build();
    }

    public void addGoodHandlerBeforeStart(CountingHandler handler) {

        try (final EventLoop eventGroup = createEventGroup()) {
            assertEquals(EVENT_GROUP_NAME, eventGroup.name());

            // Add the handler.
            eventGroup.addHandler(handler);

            // Start the loop.
            eventGroup.start();
            Waiters.waitForCondition("Wait for eventGroup started", eventGroup::isAlive, 5000);
            Waiters.waitForCondition("Wait for handler loopStarted called:" + handler.priority, () -> (handler.loopStartedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(0, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventGroup.stop();
            Waiters.waitForCondition("Wait for eventGroup stopped", eventGroup::isStopped, 5000);

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
    public void testGoodHandlerAddedBeforeStart() {

        //addGoodHandlerBeforeStart(new CountingHandler(HandlerPriority.MONITOR));

        for(HandlerPriority priority : HandlerPriority.values()) {
            addGoodHandlerBeforeStart(new CountingHandler(priority));
        }
    }

    void addGoodHandlerAfterStart(CountingHandler handler) {
        try (final EventLoop eventGroup = createEventGroup()) {

            // Start the loop.
            eventGroup.start();
            Waiters.waitForCondition("Wait for loop started:" + handler.priority, eventGroup::isAlive, 5000);

            // Add the handler.
            eventGroup.addHandler(handler);

            Waiters.waitForCondition("Wait handler loopStarted called:" + handler.priority,() -> (handler.loopStartedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(0, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventGroup.stop();
            Waiters.waitForCondition("Wait for loop stopped:" + handler.priority, eventGroup::isStopped, 5000);

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
    public void testGoodHandlerAddedAfterStart() {
        for(HandlerPriority priority : HandlerPriority.values()) {
            addGoodHandlerAfterStart(new CountingHandler(priority));
        }
    }

    void addThrowingHandlerLoopStartedBeforeStart(CountingHandler handler) {
        try (final EventLoop eventGroup = createEventGroup()) {
            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // Add handler before loop has started. loopStarted not called yet.
            eventGroup.addHandler(handler);

            // Start the loop. loopStarted called and exception thrown. Expect handler to be removed.
            eventGroup.start();

            // Wait for loop to start and handler to be removed.
            Waiters.waitForCondition("Wait for loop started:" + handler.priority, eventGroup::isAlive, 5000);
            Waiters.waitForCondition("Wait for handler close called:" + handler.priority, () -> (handler.closeCalled() > 0), 5000);
            //Waiters.waitForCondition("Handler should be removed", () -> (eventLoop.handlerCount() == 0), 5000);

            assertTrue(eventGroup.isAlive());
            //assertTrue(eventGroup.newHandlers.isEmpty());

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(1, handler.loopFinishedCalled());
            assertEquals(1, handler.closeCalled());

            // Handler has been removed.
            //assertEquals(0, eventGroup.handlerCount());

            // Event loop is running.
            // Expect the eventLoop to continue.
            assertTrue(eventGroup.isAlive());
            assertFalse(eventGroup.isStopped());
            assertFalse(eventGroup.isClosing());
            assertFalse(eventGroup.isClosed());
        }
    }

    // ExpectException does not like looping through the test case. Using individual test cases.

    @Test
    public void testThrowingHandlerAddedBeforeStartMonitor() {
        addThrowingHandlerLoopStartedBeforeStart(new ThrowingHandler(HandlerPriority.MONITOR, false, false));
    }

    @Test
    public void testThrowingHandlerAddedBeforeStartHigh() {
        addThrowingHandlerLoopStartedBeforeStart(new ThrowingHandler(HandlerPriority.HIGH, false, false));
    }

    @Test
    public void testThrowingHandlerAddedBeforeStartMedium() {
        addThrowingHandlerLoopStartedBeforeStart(new ThrowingHandler(HandlerPriority.MEDIUM, false, false));
    }

    @Test
    public void testThrowingHandlerAddedBeforeStartTimer() {
        addThrowingHandlerLoopStartedBeforeStart(new ThrowingHandler(HandlerPriority.TIMER, false, false));
    }

    @Test
    public void testThrowingHandlerAddedBeforeStartDaemon() {
        addThrowingHandlerLoopStartedBeforeStart(new ThrowingHandler(HandlerPriority.DAEMON, false, false));
    }

    @Test
    public void testThrowingHandlerAddedBeforeStartBlocking() {
        addThrowingHandlerLoopStartedBeforeStart(new ThrowingHandler(HandlerPriority.BLOCKING, false, false));
    }

    @Test
    public void testThrowingHandlerAddedBeforeStartConcurrent() {
        addThrowingHandlerLoopStartedBeforeStart(new ThrowingHandler(HandlerPriority.CONCURRENT, false, false));
    }

    void addThrowingHandlerAfterEventLoopStarted(CountingHandler handler) {
        try (final EventLoop eventGroup = createEventGroup()) {
            expectException(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            expectException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            expectException(HANDLER_CLOSE_EXCEPTION_TXT);

            // start the event loop with no handlers.
            eventGroup.start();

            // Wait for the handler to be started.
            Waiters.waitForCondition("Event loop started", eventGroup::isAlive, 5000);

            // Add the new handler. It should be picked up by the event loop and removed after exception in loopStarted.
            eventGroup.addHandler(handler);

            // Wait for the handler to be removed.
            Waiters.waitForCondition("Wait handler loopStarted called:" + handler.priority,() -> (handler.closeCalled() > 0), 5000);

            // Event loop is running.
            assertTrue(eventGroup.isAlive());
            assertFalse(eventGroup.isStopped());
            assertFalse(eventGroup.isClosing());
            assertFalse(eventGroup.isClosed());
        }
    }

    @Test
    public void testThrowingHandlerAddedAfterStartMonitor() {
        addThrowingHandlerAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.MONITOR, false, false));
    }

    @Test
    public void testThrowingHandlerAddedAfterStartHigh() {
        addThrowingHandlerAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.HIGH, false, false));
    }

    @Test
    public void testThrowingHandlerAddedAfterStartMedium() {
        addThrowingHandlerAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.MEDIUM, false, false));
    }

    @Test
    public void testThrowingHandlerAddedAfterStartTimer() {
        addThrowingHandlerAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.TIMER, false, false));
    }

    @Test
    public void testThrowingHandlerAddedAfterStartDaemon() {
        addThrowingHandlerAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.DAEMON, false, false));
    }

    @Test
    public void testThrowingHandlerAddedAfterStartBlocking() {
        addThrowingHandlerAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.BLOCKING, false, false));
    }

    @Test
    public void testThrowingHandlerAddedAfterStartConcurrent() {
        addThrowingHandlerAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.CONCURRENT, false, false));
    }

    void addThrowingEventLoopAfterEventLoopStarted(CountingHandler handler) {
        try (final EventLoop eventGroup = createEventGroup()) {
            expectException(HANDLER_EVENT_LOOP_EXCEPTION_TXT);

            // start the event loop with no handlers.
            eventGroup.start();

            // Wait for the handler to be started.
            Waiters.waitForCondition("Event loop started", eventGroup::isAlive, 5000);

            // Add the new handler. It should be picked up by the event loop and exception in eventLoop logged and ignored.
            eventGroup.addHandler(handler);
            Waiters.waitForCondition("Wait handler loopStarted called:" + handler.priority,() -> (handler.loopStartedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled());
            assertEquals(0, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
            assertNotNull(handler.eventLoop());

            // Stop the loop.
            eventGroup.stop();
            Waiters.waitForCondition("Wait for loop stopped:" + handler.priority, eventGroup::isStopped, 5000);

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
    public void testThrowingEventLoopAddedAfterStartMonitor() {
        addThrowingEventLoopAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.MONITOR, true, false));
    }

    @Test
    public void testThrowingEventLoopAddedAfterStartHigh() {
        addThrowingEventLoopAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.HIGH, true, false));
    }

    @Test
    public void testThrowingEventLoopAddedAfterStartMedium() {
        addThrowingEventLoopAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.MEDIUM, true, false));
    }

    @Test
    public void testThrowingEventLoopAddedAfterStartTimer() {
        addThrowingEventLoopAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.TIMER, true, false));
    }

    @Test
    public void testThrowingEventLoopAddedAfterStartDaemon() {
        addThrowingEventLoopAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.DAEMON, true, false));
    }

    @Test
    public void testThrowingEventLoopAddedAfterStartBlocking() {
        addThrowingEventLoopAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.BLOCKING, true, false));
    }

    @Test
    public void testThrowingEventLoopAddedAfterStartConcurrent() {
        addThrowingEventLoopAfterEventLoopStarted(new ThrowingHandler(HandlerPriority.CONCURRENT, true, false));
    }

}
