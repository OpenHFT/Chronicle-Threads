/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.*;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.*;

import static net.openhft.chronicle.threads.EventHandlerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests how an {@link EventGroup} registers and removes handlers.
 *
 * <p>Handlers may be added before the loop starts or while it is running.
 * A well behaved handler should have {@code loopStarted},
 * {@code loopFinished} and {@code close} invoked in order. If a handler throws
 * during {@code loopStarted} or while being assigned an {@link EventLoop}
 * the group discards it and continues running.
 */
class EventGroupHandlerTest extends ThreadsTestCommon {

    @BeforeEach
    void beforeAll() {
        ignoreException("Monitoring a task which has finished ");
        // Initial delay defaults to 10secs. Set to 10ms for testing.
        setMonitorInitialDelayMs(10);
    }

    @AfterEach
    void afterEach() {
        setMonitorInitialDelayMs(10_000);
    }

    private static final String EVENT_GROUP_NAME = "test";

    private EventGroup createEventGroup() {
        return EventGroup.builder().withName(EVENT_GROUP_NAME).withDaemon(true).build();
    }

    private void addGoodHandlerBeforeStart(CountingHandler handler) {

        try (final EventLoop eventGroup = createEventGroup()) {
            assertEquals(EVENT_GROUP_NAME, eventGroup.name(), "event group should have the expected name configured at creation");

            // Add the handler.
            eventGroup.addHandler(handler);

            // Start the loop.
            eventGroup.start();
            Waiters.waitForCondition("Wait for eventGroup started", eventGroup::isAlive, 5000);
            Waiters.waitForCondition("Wait for handler loopStarted called:" + handler.priority, () -> (handler.loopStartedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled(), "handler registered before start should have received loopStarted callback exactly once after event group start (priority=" + handler.priority + ")");
            assertEquals(0, handler.loopFinishedCalled(), "handler registered before start should not have received loopFinished callback while event group is running (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "handler registered before start should not have been closed while event group is running (priority=" + handler.priority + ")");
            assertNotNull(handler.eventLoop(), "handler registered before start should have been assigned a non-null event loop reference (priority=" + handler.priority + ")");

            // Stop the loop.
            eventGroup.stop();
            Waiters.waitForCondition("Wait for eventGroup stopped", eventGroup::isStopped, 5000);
            Waiters.waitForCondition("Wait for handler loopFinished called:" + handler.priority, () -> (handler.loopFinishedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled(), "handler registered before start should have received loopStarted callback exactly once during its lifecycle (priority=" + handler.priority + ")");
            assertEquals(1, handler.loopFinishedCalled(), "handler registered before start should have received loopFinished callback exactly once after event group stop (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "handler registered before start should not have been closed yet while inside try-with-resources block (priority=" + handler.priority + ")");
        }

        // Check the handler.
        assertEquals(1, handler.loopStartedCalled(), "handler should have completed loopStarted lifecycle callback exactly once after event group closed (priority=" + handler.priority + ")");
        assertEquals(1, handler.loopFinishedCalled(), "handler should have completed loopFinished lifecycle callback exactly once after event group closed (priority=" + handler.priority + ")");
        assertEquals(1, handler.closeCalled(), "handler should have been closed exactly once after event group disposal via try-with-resources (priority=" + handler.priority + ")");
    }

    @Test
    void testGoodHandlerAddedBeforeStart() {
        for (HandlerPriority priority : HandlerPriority.values()) {
            CountingHandler handler = new CountingHandler(priority);
            addGoodHandlerBeforeStart(handler);
            assertEquals(1, handler.closeCalled(), "handler registered before start should be closed exactly once after full lifecycle (priority=" + priority + ")");
        }
    }

    private void addGoodHandlerAfterStart(CountingHandler handler) {
        try (final EventLoop eventGroup = createEventGroup()) {

            // Start the loop.
            eventGroup.start();
            Waiters.waitForCondition("Wait for loop started:" + handler.priority, eventGroup::isAlive, 5000);

            // Add the handler.
            eventGroup.addHandler(handler);

            Waiters.waitForCondition("Wait handler loopStarted called:" + handler.priority,() -> (handler.loopStartedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled(), "handler registered after start should have received loopStarted callback exactly once after dynamic registration (priority=" + handler.priority + ")");
            assertEquals(0, handler.loopFinishedCalled(), "handler registered after start should not have received loopFinished callback while event group is running (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "handler registered after start should not have been closed while event group is running (priority=" + handler.priority + ")");
            assertNotNull(handler.eventLoop(), "handler registered after start should have been assigned a non-null event loop reference (priority=" + handler.priority + ")");

            // Stop the loop.
            eventGroup.stop();
            Waiters.waitForCondition("Wait for loop stopped:" + handler.priority, eventGroup::isStopped, 5000);
            Waiters.waitForCondition("Wait for handler loopFinished called:" + handler.priority, () -> (handler.loopFinishedCalled() > 0), 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled(), "handler registered after start should have received loopStarted callback exactly once during its lifecycle (priority=" + handler.priority + ")");
            assertEquals(1, handler.loopFinishedCalled(), "handler registered after start should have received loopFinished callback exactly once after event group stop (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "handler registered after start should not have been closed yet while inside try-with-resources block (priority=" + handler.priority + ")");
        }

        // Check the handler.
        assertEquals(1, handler.loopStartedCalled(), "handler added after start should have received loopStarted callback exactly once after event group closed (priority=" + handler.priority + ")");
        assertEquals(1, handler.loopFinishedCalled(), "handler added after start should have received loopFinished callback exactly once after event group closed (priority=" + handler.priority + ")");
        assertEquals(1, handler.closeCalled(), "handler added after start should have been closed exactly once after event group disposal (priority=" + handler.priority + ")");
    }

    @Test
    void testGoodHandlerAddedAfterStart() {
        for (HandlerPriority priority : HandlerPriority.values()) {
            CountingHandler handler = new CountingHandler(priority);
            addGoodHandlerAfterStart(handler);
            assertEquals(1, handler.closeCalled(), "handler registered after start should be closed exactly once after full lifecycle (priority=" + priority + ")");
        }
    }

    private void addThrowingHandlerLoopStartedBeforeStart(CountingHandler handler) {
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

            // Exceptions should be thrown.
            assertExceptionThrown(HANDLER_LOOP_STARTED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_LOOP_FINISHED_EXCEPTION_TXT);
            assertExceptionThrown(HANDLER_CLOSE_EXCEPTION_TXT);

            // Methods called once.
            assertEquals(1, handler.loopStartedCalled(), "throwing handler should have received loopStarted callback exactly once before being removed (priority=" + handler.priority + ")");
            assertEquals(1, handler.loopFinishedCalled(), "throwing handler should have received loopFinished callback exactly once during cleanup after exception (priority=" + handler.priority + ")");
            assertEquals(1, handler.closeCalled(), "throwing handler should have been closed exactly once after exception during removal (priority=" + handler.priority + ")");

            // Expect the eventLoop to continue.
            assertTrue(eventGroup.isAlive(), "event group should remain alive and running despite handler throwing exception during loopStarted");
            assertFalse(eventGroup.isStopped(), "event group should not be stopped after handler exception in loopStarted");
            assertFalse(eventGroup.isClosing(), "event group should not be closing after handler exception in loopStarted");
            assertFalse(eventGroup.isClosed(), "event group should not be closed after handler exception in loopStarted");
        }
    }

    // ExpectException does not like looping through the test case. Using individual test cases.

    @Test
    void testThrowingHandlerAddedBeforeStartMonitor() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.MONITOR, false, false);
        addThrowingHandlerLoopStartedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "MONITOR priority handler throwing exception before start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedBeforeStartHigh() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.HIGH, false, false);
        addThrowingHandlerLoopStartedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "HIGH priority handler throwing exception before start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedBeforeStartMedium() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.MEDIUM, false, false);
        addThrowingHandlerLoopStartedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "MEDIUM priority handler throwing exception before start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedBeforeStartTimer() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.TIMER, false, false);
        addThrowingHandlerLoopStartedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "TIMER priority handler throwing exception before start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedBeforeStartDaemon() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.DAEMON, false, false);
        addThrowingHandlerLoopStartedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "DAEMON priority handler throwing exception before start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedBeforeStartBlocking() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.BLOCKING, false, false);
        addThrowingHandlerLoopStartedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "BLOCKING priority handler throwing exception before start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedBeforeStartConcurrent() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.CONCURRENT, false, false);
        addThrowingHandlerLoopStartedBeforeStart(handler);
        assertEquals(1, handler.closeCalled(), "CONCURRENT priority handler throwing exception before start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    private void addThrowingHandlerAfterEventLoopStarted(CountingHandler handler) {
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
            assertTrue(eventGroup.isAlive(), "event group should remain alive and running after removing handler that threw exception in loopStarted");
            assertFalse(eventGroup.isStopped(), "event group should not be stopped after removing handler that threw exception in loopStarted");
            assertFalse(eventGroup.isClosing(), "event group should not be closing after removing handler that threw exception in loopStarted");
            assertFalse(eventGroup.isClosed(), "event group should not be closed after removing handler that threw exception in loopStarted");
        }
    }

    @Test
    void testThrowingHandlerAddedAfterStartMonitor() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.MONITOR, false, false);
        addThrowingHandlerAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "MONITOR priority handler throwing exception after start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedAfterStartHigh() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.HIGH, false, false);
        addThrowingHandlerAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "HIGH priority handler throwing exception after start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedAfterStartMedium() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.MEDIUM, false, false);
        addThrowingHandlerAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "MEDIUM priority handler throwing exception after start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedAfterStartTimer() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.TIMER, false, false);
        addThrowingHandlerAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "TIMER priority handler throwing exception after start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedAfterStartDaemon() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.DAEMON, false, false);
        addThrowingHandlerAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "DAEMON priority handler throwing exception after start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedAfterStartBlocking() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.BLOCKING, false, false);
        addThrowingHandlerAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "BLOCKING priority handler throwing exception after start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingHandlerAddedAfterStartConcurrent() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.CONCURRENT, false, false);
        addThrowingHandlerAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "CONCURRENT priority handler throwing exception after start should be closed exactly once after removal (priority=" + handler.priority + ")");
    }

    private void addThrowingEventLoopAfterEventLoopStarted(CountingHandler handler) {
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
            assertEquals(1, handler.loopStartedCalled(), "handler throwing in eventLoop should have received loopStarted callback exactly once (priority=" + handler.priority + ")");
            assertEquals(0, handler.loopFinishedCalled(), "handler throwing in eventLoop should not have received loopFinished callback while event group is running (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "handler throwing in eventLoop should not have been closed while event group is running (priority=" + handler.priority + ")");
            assertNotNull(handler.eventLoop(), "handler throwing in eventLoop should have been assigned a non-null event loop reference (priority=" + handler.priority + ")");

            // Stop the loop.
            eventGroup.stop();
            Waiters.waitForCondition("Wait for loop stopped:" + handler.priority, eventGroup::isStopped, 5000);

            // Check the handler.
            assertEquals(1, handler.loopStartedCalled(), "handler throwing in eventLoop should have received loopStarted callback exactly once after stop (priority=" + handler.priority + ")");
            assertEquals(1, handler.loopFinishedCalled(), "handler throwing in eventLoop should have received loopFinished callback exactly once after event group stop (priority=" + handler.priority + ")");
            assertEquals(0, handler.closeCalled(), "handler throwing in eventLoop should not have been closed yet while inside try-with-resources block (priority=" + handler.priority + ")");
        }

        // Check the handler.
        assertEquals(1, handler.loopStartedCalled(), "handler throwing in eventLoop should have received loopStarted callback exactly once after event group closed (priority=" + handler.priority + ")");
        assertEquals(1, handler.loopFinishedCalled(), "handler throwing in eventLoop should have received loopFinished callback exactly once after event group closed (priority=" + handler.priority + ")");
        assertEquals(1, handler.closeCalled(), "handler throwing in eventLoop should have been closed exactly once after event group disposal (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingEventLoopAddedAfterStartMonitor() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.MONITOR, true, false);
        addThrowingEventLoopAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "MONITOR priority handler throwing exception in eventLoop should be closed exactly once after full lifecycle (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingEventLoopAddedAfterStartHigh() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.HIGH, true, false);
        addThrowingEventLoopAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "HIGH priority handler throwing exception in eventLoop should be closed exactly once after full lifecycle (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingEventLoopAddedAfterStartMedium() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.MEDIUM, true, false);
        addThrowingEventLoopAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "MEDIUM priority handler throwing exception in eventLoop should be closed exactly once after full lifecycle (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingEventLoopAddedAfterStartTimer() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.TIMER, true, false);
        addThrowingEventLoopAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "TIMER priority handler throwing exception in eventLoop should be closed exactly once after full lifecycle (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingEventLoopAddedAfterStartDaemon() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.DAEMON, true, false);
        addThrowingEventLoopAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "DAEMON priority handler throwing exception in eventLoop should be closed exactly once after full lifecycle (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingEventLoopAddedAfterStartBlocking() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.BLOCKING, true, false);
        addThrowingEventLoopAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "BLOCKING priority handler throwing exception in eventLoop should be closed exactly once after full lifecycle (priority=" + handler.priority + ")");
    }

    @Test
    void testThrowingEventLoopAddedAfterStartConcurrent() {
        ThrowingHandler handler = new ThrowingHandler(HandlerPriority.CONCURRENT, true, false);
        addThrowingEventLoopAfterEventLoopStarted(handler);
        assertEquals(1, handler.closeCalled(), "CONCURRENT priority handler throwing exception in eventLoop should be closed exactly once after full lifecycle (priority=" + handler.priority + ")");
    }
}
