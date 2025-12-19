/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.*;
import net.openhft.chronicle.core.threads.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Collections.singleton;
import static net.openhft.chronicle.core.io.Closeable.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the start and stop behaviour of an {@link EventGroup}.
 *
 * <p>The suite ensures that:
 * <ul>
 * <li>closing a paused blocking loop stops the group;</li>
 * <li>closing without starting marks the group as stopped;</li>
 * <li>{@code stop()} can be called more than once;</li>
 * <li>handlers remain active until the group is closed;</li>
 * <li>no extra threads remain after {@code stop()}.</li>
 * </ul>
 */
class EventGroupTest extends ThreadsTestCommon {
    private static final RuntimeException RUNTIME_EXCEPTION = new RuntimeException("some random text");
    private final List<EventHandlerProbe> handlers = new ArrayList<>();

    @BeforeEach
    void handlersInit() {
        ignoreException("Monitoring a task which has finished ");
        setMonitorInitialDelayMs(1);
    }

    @Override
    public void preAfter() throws InterruptedException {
        setMonitorInitialDelayMs(10_000);

        for (EventHandlerProbe handler : this.handlers)
            handler.assertClosed();
        handlers.forEach(EventHandlerProbe::checkCloseOrder);
    }

    @Timeout(5)
    @Test
    void testEventLoopName() {
        try (final EventLoop eventGroup = EventGroup.builder()
                .withName("my-eg/")
                .build()) {
            assertEquals("my-eg", eventGroup.name(), "event group name should have trailing slash normalized");
        }
    }

    @SuppressWarnings("InstantiatingAThreadWithDefaultRunMethod")
    @Timeout(5)
    @Test
    void testSimpleEventGroupTest() throws InterruptedException {

        final AtomicInteger value = new AtomicInteger();

        Thread t;
        try (final EventLoop eventGroup = EventGroup.builder()
                .withPriorities(HandlerPriority.MEDIUM)
                .build()) {
            eventGroup.start();
            t = new Thread();
            t.start();
            eventGroup.addHandler(() -> {
                if (value.get() == 10)
                    // throw this if you don't wish to be called back
                    throw new InvalidEventHandlerException();
                value.incrementAndGet();
                return true;
            });

            final long start = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted() && value.get() != 10) {
                Jvm.pause(10);
            }

            assertTrue(System.currentTimeMillis() < start + TimeUnit.SECONDS.toMillis(5), "handler reached expected value within timeout");

            for (int i = 0; i < 10; i++) {
                assertEquals(10, value.get(), "handler should maintain expected value of 10 after reaching it");
                Jvm.pause(1);
            }
        }
        t.join(100);
        try {
            assertFalse(t.isAlive(), "background thread should have terminated after event group closed");
        } finally {
            t.interrupt();
        }
    }

    @Timeout(5)
    @Test
    void testSimpleEventGroupPrivateGroup() {
        assertDoesNotThrow(() -> doTestSimpleEventGroup(true), "private event group should close cleanly when closed from within handler");
    }

    @Timeout(5)
    @Test
    void testSimpleEventGroupNonPrivateGroup() {
        assertDoesNotThrow(() -> doTestSimpleEventGroup(false), "non-private event group should close cleanly when closed from within handler");
    }

    private void doTestSimpleEventGroup(boolean privateGroup) {
        if (!privateGroup)
            ignoreException("Attempting to close private:false from within!");
        try (final EventLoop eventGroup = EventGroup.builder()
                .withName("private:" + privateGroup)
                .withPriorities(HandlerPriority.MEDIUM)
                .withPrivateGroup(privateGroup)
                .withPauser(Pauser.millis(10))
                .build()) {
            eventGroup.start();
            eventGroup.addHandler(() -> {
                closeQuietly(eventGroup);
                return false;
            });
        }
    }

    @Timeout(5)
    @Test
    void testClosePausedBlockingEventLoop() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.addHandler(new PausingBlockingEventHandler());
        eventGroup.close();
        assertTrue(eventGroup.isClosed(), "event group should be closed after closing with paused blocking handler");
        assertTrue(eventGroup.isStopped(), "event group should be stopped after closing with paused blocking handler");
    }

    @Timeout(5)
    @Test
    void testCloseAwaitTermination() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.close();
        assertTrue(eventGroup.isClosed(), "event group should be closed after awaiting termination");
        assertTrue(eventGroup.isStopped(), "event group should be stopped after awaiting termination");
    }

    @Timeout(5)
    @Test
    void testCloseStopAwaitTermination() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.stop();
        eventGroup.close();
        assertTrue(eventGroup.isClosed(), "event group should be closed after explicit stop then close");
        assertTrue(eventGroup.isStopped(), "event group should be stopped after explicit stop then close");
    }

    @Timeout(5)
    @Test
    void testCloseStopIdempotent() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.stop();
        eventGroup.stop();
        eventGroup.close();
        assertTrue(eventGroup.isClosed(), "event group should be closed after repeated stop calls");
        assertTrue(eventGroup.isStopped(), "event group should be stopped after repeated stop calls");
    }

    @Timeout(5)
    @Test
    void testCloseAwaitTerminationWithoutStarting() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.close();
        assertTrue(eventGroup.isClosed(), "event group should be closed when closed without starting");
        assertTrue(eventGroup.isStopped(), "event group should be stopped when closed without starting");
    }

    @Timeout(5)
    @Test
    void checkNoThreadsCreatedIfEventGroupNotStarted() {
        final ThreadDump threadDump = new ThreadDump();
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.EventHandlerProbe(hp));
            threadDump.assertNoNewThreads();
        }
    }

    @Timeout(5)
    @Test
    void checkAllEventHandlerTypesStartAndStop() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.EventHandlerProbe(hp));
            eventGroup.start();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
        }
    }

    @Timeout(5)
    @Test
    void checkNoThreadsAfterStopCalled() throws InterruptedException {
        final ThreadDump threadDump = new ThreadDump();
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.EventHandlerProbe(hp));
            eventGroup.start();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
            eventGroup.stop();
            threadDump.assertNoNewThreads();
            handlers.forEach(testHandler -> assertNotEquals(0, testHandler.loopFinishedNS.get(), "handler loop should have finished after stop (priority=" + testHandler.priority + ")"));
        }
    }

    @Timeout(5)
    @Test
    void checkHandlersNotClosedAfterStop() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.EventHandlerProbe(hp));
            eventGroup.start();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
            eventGroup.stop();
            handlers.forEach(testHandler -> assertFalse(testHandler.isClosing(), "handler not closing after stop (priority=" + testHandler.priority + ")"));
        }
        handlers.forEach(testHandler -> assertTrue(testHandler.isClosed(), "handler closed after eventGroup close (priority=" + testHandler.priority + ")"));
    }

    @Timeout(5)
    @Test
    void checkHandlersClosedImmediatelyOnInvalidHandlerException() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.EventHandlerProbe(hp, ExceptionType.INVALID_EVENT_HANDLER));
            eventGroup.start();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertClosed();
        }
    }

    @Timeout(5)
    @Test
    void checkAllEventHandlerTypesStartAndStopAddAgain() throws InterruptedException {
        expectException("Only one high handler supported was EventHandlerProbe");
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.EventHandlerProbe(hp));
            for (EventHandlerProbe handler : this.handlers) {
                handler.assertInstalled();
                assertEquals(1, handler.started.getCount(), "handler should not have started yet after install (priority=" + handler.priority + ")");
            }
            eventGroup.start();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
            // add more after start
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.EventHandlerProbe(hp));
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
        }
    }

    @Timeout(5)
    @Test
    void checkExecutedInOrderOfPriorityInline() throws InterruptedException {
        checkExecutedOrderOfPriority(HandlerPriority.MEDIUM, HandlerPriority.HIGH, HandlerPriority.MEDIUM);
    }

    @Timeout(5)
    @Test
    void checkExecutedInOrderOfPriorityLoop() throws InterruptedException {
        checkExecutedOrderOfPriority(HandlerPriority.MEDIUM, HandlerPriority.MEDIUM, HandlerPriority.HIGH, HandlerPriority.MEDIUM, HandlerPriority.MEDIUM, HandlerPriority.MEDIUM);
    }

    private void checkExecutedOrderOfPriority(HandlerPriority... priorities) throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority priority : priorities)
                eventGroup.addHandler(new EventHandlerProbe(priority));
            eventGroup.start();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
            this.handlers.sort(Comparator.comparing(EventHandlerProbe::priority));
            long l0;
            long l1;
            do {
                Jvm.pause(1);
                l0 = this.handlers.get(0).firstActionNs.get();
                l1 = this.handlers.get(1).firstActionNs.get();
            } while (!Thread.currentThread().isInterrupted() && (l0 == 0 || l1 == 0));
            assertTrue(l0 < l1, "higher priority handler should have run before lower priority handler");
        }
        Jvm.pause(100);
        assertTrue(this.handlers.get(0).actionCalled.get() > this.handlers.get(1).actionCalled.get(), "higher priority handler should have been called more often than lower priority handler");
    }

    @Timeout(5)
    @Test
    void checkAllEventHandlerTypesStartInvalidEventHandlerException() throws InterruptedException {
        checkException(ExceptionType.INVALID_EVENT_HANDLER);
    }

    private void checkException(ExceptionType exceptionType) throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventHandlerProbe(hp, exceptionType));
            eventGroup.start();
            for (EventHandlerProbe handler : this.handlers)
                handler.assertStarted();
            Jvm.pause(100);
        }
        for (EventHandlerProbe handler : this.handlers) {
            assertEquals(1, handler.actionCalled.get(), "handler action should be called exactly once when throwing exception (priority=" + handler.priority + ")");
        }
    }

    // TODO: checkAllEventHandlerTypesContinueRuntimeException()

    @Timeout(5)
    @Test
    void testCloseAddHandler() {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            closeQuietly(eventGroup); // Direct call to close causes an unsuppressable warning in Java 21+
            for (HandlerPriority hp : HandlerPriority.values()) {
                final EventHandlerProbe handler = new EventHandlerProbe(hp);
                assertThrows(IllegalStateException.class, () -> eventGroup.addHandler(handler));
            }
            handlers.clear();
        }
    }

    @Timeout(5)
    @Test
    void testEventGroupNoCoreEventLoop() {
        final AtomicReference<EventLoop> ref = new AtomicReference<>();
        try (EventLoop eg = EventGroup.builder()
                .withConcurrentThreadsNum(0)
                .withPriorities(HandlerPriority.REPLICATION)
                .build()) {
            ref.set(eg);
            eg.unpause();
        }
        assertTrue(ref.get().isClosed(), "event group should be closed when no core event loop exists");
    }

    @Test
    void inEventLoop() {
        try (EventGroup eg = EventGroup.builder().build()) {
            eg.start();
            assertFalse(EventLoop.inEventLoop(), "test thread should not be considered an event loop thread");
            Set<HandlerPriority> priorities = new ConcurrentSkipListSet<>();
            for (HandlerPriority priority : HandlerPriority.values()) {
                eg.addHandler(new EventHandler() {
                    @Override
                    public boolean action() throws InvalidEventHandlerException {
                        try {
                            assertTrue(EventLoop.inEventLoop(), "handler thread should be in event loop for priority " + priority.name());
                            priorities.add(priority);
                        } catch (Throwable t) {
                            //noinspection CallToPrintStackTrace
                            t.printStackTrace();
                        }
                        throw new InvalidEventHandlerException("done");
                    }

                    @Override
                    public @NotNull HandlerPriority priority() {
                        return priority;
                    }
                });
            }

            EnumSet<HandlerPriority> allPriorities = EnumSet.allOf(HandlerPriority.class);
            for (int i = 1; i < 30; i++) {
                Jvm.pause(i);
                if (priorities.equals(allPriorities))
                    break;
            }
            allPriorities.removeAll(priorities);
            if (!allPriorities.isEmpty())
                fail("Priorities failed " + allPriorities);
        }
    }

    @Test
    void handlersWithASharedResourceShutdownGracefully() {
        EventGroup eventGroup = EventGroup.builder().build();
        CloseableResource resource = new CloseableResource();
        for (HandlerPriority handlerPriority : HandlerPriority.values()) {
            IntStream.of(4).forEach(i -> eventGroup.addHandler(new SharedResourceUsingHandler(resource, handlerPriority)));
        }
        eventGroup.start();
        Jvm.pause(1000);
        eventGroup.close();
        assertTrue(resource.isClosed(), "shared resource should be closed when event group closes");
    }

    @Test
    void daemonParameterShouldBeUsedWhenCreatingReplicationEventLoop() throws IllegalAccessException {
        try (final EventGroup eventGroup = EventGroup.builder()
                .withDaemon(false)
                .withPriorities(HandlerPriority.REPLICATION)
                .build()) {
            eventGroup.addHandler(new EventHandlerProbe(HandlerPriority.REPLICATION));  // replication EventLoop is lazily created
            final MediumEventLoop replication = (MediumEventLoop) Jvm.getField(EventGroup.class, "replication").get(eventGroup);
            assertFalse(replication.daemon, "replication event loop should use configured daemon setting");
        }
    }

    @Test
    void lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads() {
        lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads_ForPriorities(Arrays.stream(HandlerPriority.values()).collect(Collectors.toSet()));
        assertFalse(handlers.isEmpty(), "handlers should be created for all priorities");
        for (EventHandlerProbe handler : handlers) {
            assertNotEquals(0, handler.loopFinishedNS.get(), "loop finished timestamp should be recorded for handler (priority=" + handler.priority + ")");
        }
        // You get a MediumEventLoop instead of a VanillaEventLoop when you only have medium priority
        lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads_ForPriorities(singleton(HandlerPriority.MEDIUM));
        assertFalse(handlers.isEmpty(), "handlers should be created for medium priority");
        for (EventHandlerProbe handler : handlers) {
            assertNotEquals(0, handler.loopFinishedNS.get(), "loop finished timestamp should be recorded for medium priority handler");
        }
    }

    private void lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads_ForPriorities(Set<HandlerPriority> priorities) {
        handlers.clear();
        EventGroup eventGroup = EventGroup.builder().withPriorities(priorities).build();
        for (HandlerPriority handlerPriority : priorities) {
            final EventHandlerProbe handler = new EventHandlerProbe(handlerPriority);
            eventGroup.addHandler(handler);
        }
        handlers.forEach(handler -> assertEquals(0, handler.loopStartedNS.get(), handler.priority + " was loopStarted before loop started, priorities=" + priorities));
        eventGroup.start();
        handlers.forEach(handler -> assertEquals(0, handler.loopFinishedNS.get(), handler.priority + " was loopFinished before loop finished, priorities=" + priorities));
        Jvm.pause(1000);
        handlers.forEach(handler -> assertNotEquals(0, handler.loopStartedNS.get(), handler.priority + " was not loopStarted when loop started, priorities=" + priorities));
        eventGroup.close();
        handlers.forEach(handler -> assertNotEquals(0, handler.loopFinishedNS.get(), handler.priority + " was not loopFinished when loop finished, priorities=" + priorities));
    }

    private static Stream<List<HandlerPriority>> egCloseParams() {
        return Stream.of(
                Collections.singletonList(HandlerPriority.MEDIUM),
                Arrays.asList(HandlerPriority.MEDIUM, HandlerPriority.HIGH),
                Arrays.asList(HandlerPriority.TIMER, HandlerPriority.HIGH),
                Arrays.asList(HandlerPriority.MEDIUM, HandlerPriority.BLOCKING, HandlerPriority.TIMER),
                Arrays.asList(HandlerPriority.MEDIUM, HandlerPriority.BLOCKING, HandlerPriority.TIMER, HandlerPriority.HIGH)
        );
    }

    /**
     * Run the event group and attempt to stop each of the inner event loops by handler priority
     */
    @ParameterizedTest()
    @MethodSource("egCloseParams")
    void closeEventGroupInWithinAndEventLoopThrowsException(List<HandlerPriority> priorities) {
        EventGroup eg = EventGroupBuilder.builder().build();
        try {
            Map<HandlerPriority, AtomicBoolean> eventHandlerFinishedForPriority = new EnumMap<>(HandlerPriority.class);
            Map<HandlerPriority, AtomicBoolean> exceptionThrownInHandlerForPriority = new EnumMap<>(HandlerPriority.class);

            for (final HandlerPriority priority : priorities) {

                if (exceptionThrownInHandlerForPriority.containsKey(priority)) {
                    continue; // dont test overlapping priorities
                }

                AtomicBoolean eventHandlerFinished = eventHandlerFinishedForPriority.computeIfAbsent(priority, p -> new AtomicBoolean());
                AtomicBoolean exceptionThrownInHandler = exceptionThrownInHandlerForPriority.computeIfAbsent(priority, p -> new AtomicBoolean());

                EventHandler closingEventHandler = new EventHandler() {
                    @Override
                    public boolean action() throws InvalidEventHandlerException, InvalidMarshallableException {
                        try {
                            eg.close();
                            return true;
                        } catch (ThreadingIllegalStateException e) {
                            exceptionThrownInHandler.set(true);
                            throw InvalidEventHandlerException.reusable();
                        }
                    }

                    @Override
                    public void loopFinished() {
                        eventHandlerFinished.set(true);
                    }

                    @Override
                    public @NotNull HandlerPriority priority() {
                        return priority;
                    }
                };

                eg.addHandler(closingEventHandler);
            }
            eg.start();

            long timeoutTime = System.currentTimeMillis() + 500;
            while (!exceptionThrownInHandlerForPriority.values().stream().allMatch(AtomicBoolean::get)) {
                if (System.currentTimeMillis() > timeoutTime) {
                    final List<HandlerPriority> handlerPrioritiesThatDidntFinish = eventHandlerFinishedForPriority.keySet().stream().filter(k -> !eventHandlerFinishedForPriority.get(k).get()).collect(Collectors.toList());
                    if (handlerPrioritiesThatDidntFinish.isEmpty()) {
                        Assertions.fail("Event group didn't throw an exception when attempting to close!");
                    } else {
                        Assertions.fail("Handlers for " + handlerPrioritiesThatDidntFinish + " didn't finish");
                    }
                }
                Jvm.pause(10);
            }

            assertTrue(eg.isAlive(), "event group should remain alive after handler exceptions");
            assertFalse(eg.isStopped(), "event group should not be stopped after handler exceptions");
            assertFalse(eg.isClosed(), "event group should not be closed after handler exceptions");
            assertFalse(eg.isClosing(), "event group should not be closing after handler exceptions");
        } finally {
            eg.close();

            assertTrue(eg.isClosed(), "event group should be closed in cleanup");
        }
    }

    static class CloseableResource extends AbstractCloseable {

        CloseableResource() {
            singleThreadedCheckDisabled(true);
        }

        @Override
        protected void performClose() throws IllegalStateException {
            Jvm.startup().on(CloseableResource.class, "Being closed!");
        }

        void use() {
            throwExceptionIfClosed();
        }
    }

    static class SharedResourceUsingHandler extends AbstractCloseable implements EventHandler {

        private final CloseableResource closeableResource;
        private final HandlerPriority priority;

        SharedResourceUsingHandler(CloseableResource closeableResource, HandlerPriority priority) {
            this.closeableResource = closeableResource;
            this.priority = priority;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return priority;
        }

        @Override
        public boolean action() {
            Jvm.pause(ThreadLocalRandom.current().nextInt(10));
            if (closeableResource.isClosing()) {
                Jvm.error().on(SharedResourceUsingHandler.class, "Handler with priority " + priority + " interacting with closed resource");
            }
            closeableResource.use();
            return true;
        }

        @Override
        protected void performClose() {
            closeableResource.close();
        }
    }

    enum ExceptionType {
        NONE {
            @Override
            void throwIt() {
            }
        },
        INVALID_EVENT_HANDLER {
            @Override
            void throwIt() throws InvalidEventHandlerException {
                throw new InvalidEventHandlerException();
            }
        },
        RUNTIME {
            @Override
            void throwIt() {
                throw RUNTIME_EXCEPTION;
            }
        };

        abstract void throwIt() throws InvalidEventHandlerException;
    }

    private static class PausingBlockingEventHandler implements EventHandler {
        @Override
        public boolean action() {
            LockSupport.parkNanos(Long.MAX_VALUE);
            return false;
        }

        @NotNull
        @Override
        public HandlerPriority priority() {
            return HandlerPriority.BLOCKING;
        }
    }

    class EventHandlerProbe extends SimpleCloseable implements EventHandler, Closeable {
        final CountDownLatch installed = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicLong loopFinishedNS = new AtomicLong();
        final AtomicLong loopStartedNS = new AtomicLong();
        final AtomicLong closedNS = new AtomicLong();
        final AtomicLong firstActionNs = new AtomicLong();
        final HandlerPriority priority;
        final ExceptionType exceptionType;
        final AtomicInteger actionCalled = new AtomicInteger();

        EventHandlerProbe(HandlerPriority priority) {
            this(priority, ExceptionType.NONE);
        }

        EventHandlerProbe(HandlerPriority priority, ExceptionType exceptionType) {
            this.priority = priority;
            this.exceptionType = exceptionType;
            handlers.add(this);
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            // // System.out.println("action " + priority + " " + super.toString());
            assertEquals(0, installed.getCount(), "event loop should be installed before first action call (priority=" + priority + ")");

            actionCalled.incrementAndGet();
            exceptionType.throwIt();
            if (priority == HandlerPriority.BLOCKING)
                LockSupport.park();
            this.firstActionNs.compareAndSet(0, System.nanoTime());
            Jvm.pause(1);
            return false;
        }

        @Override
        public void loopStarted() {
            assertTrue(loopStartedNS.compareAndSet(0, System.nanoTime()), "loop started should be called exactly once (handler=" + this + ")");
            started.countDown();
            assertTrue(EventLoop.inEventLoop(), "loop started should be called on event loop thread (called on `"
                    + Thread.currentThread().getName()
                    + "`, priority=" + priority + ")");
        }

        @NotNull
        @Override
        public HandlerPriority priority() {
            return priority;
        }

        @Override
        public void eventLoop(EventLoop eventLoop) {
            installed.countDown();
        }

        @Override
        public void loopFinished() {
            assertTrue(loopFinishedNS.compareAndSet(0, System.nanoTime()), "loop finished should be called exactly once (handler=" + this + ")");
            assertTrue(EventLoop.inEventLoop(), "loop finished should be called on event loop thread (called on `"
                    + Thread.currentThread().getName()
                    + "`, priority=" + priority + ")");
            Jvm.busyWaitMicros(1);
        }

        @Override
        protected void performClose() {
            super.performClose();

            // // System.out.println("closed " + this);
            closed.countDown();
            assertTrue(closedNS.compareAndSet(0, System.nanoTime()), "close should be called exactly once (handler=" + this + ")");
        }

        void assertStarted() throws InterruptedException {
            assertTrue(started.await(1000, TimeUnit.MILLISECONDS), String.format("handler with priority %s should have started within timeout", priority));
        }

        void assertInstalled() throws InterruptedException {
            assertTrue(installed.await(100, TimeUnit.MILLISECONDS), String.format("handler with priority %s should have been installed within timeout", priority));
        }

        void assertClosed() throws InterruptedException {
            assertTrue(closed.await(100, TimeUnit.MILLISECONDS), String.format("handler with priority %s should have been closed within timeout", priority));
        }

        void checkCloseOrder() {
            // We call loopFinished if and only if we called loopStarted
            if (loopStartedNS.get() != 0) {
                assertNotEquals(0, loopFinishedNS.get(), "loop finished should be called when loop was started (handler=" + this + ")");
                assertNotEquals(0, closedNS.get(), "close should be called when loop was started (handler=" + this + ")");
                assertTrue(loopFinishedNS.get() < closedNS.get(), "loop finished should occur before close (handler=" + this + ")");
            } else {
                assertEquals(0, loopFinishedNS.get(), "loop finished should not be called when loop was not started");
            }
        }

        @Override
        public String toString() {
            return "EventHandlerProbe{" +
                    "priority=" + priority +
                    ", loopFinishedNS=" + loopFinishedNS +
                    ", closedNS=" + closedNS +
                    ", started=" + started.getCount() +
                    '}';
        }
    }
}
