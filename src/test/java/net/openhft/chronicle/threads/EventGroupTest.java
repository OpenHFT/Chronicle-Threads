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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.SimpleCloseable;
import net.openhft.chronicle.core.threads.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.*;

public class EventGroupTest extends ThreadsTestCommon {
    private static final RuntimeException RUNTIME_EXCEPTION = new RuntimeException("some random text");
    private final List<TestHandler> handlers = new ArrayList<>();

    @BeforeEach
    public void handlersInit() {
        ignoreException("Monitoring a task which has finished ");
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = 1;
    }

    @Override
    public void preAfter() throws InterruptedException {
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = 10_000;

        for (TestHandler handler : this.handlers)
            handler.assertClosed();
        handlers.forEach(TestHandler::checkCloseOrder);
    }

    @Timeout(5)
    @Test
    public void testEventLoopName() {
        try (final EventLoop eventGroup = EventGroup.builder()
                .withName("my-eg/")
                .build()) {
            assertEquals("my-eg", eventGroup.name());
        }
    }

    @Timeout(5)
    @Test
    public void testSimpleEventGroupTest() throws InterruptedException {

        final AtomicInteger value = new AtomicInteger();

        Thread t;
        try (final EventLoop eventGroup = EventGroup.builder()
                .withPriorities(HandlerPriority.MEDIUM)
                .build()) {
            eventGroup.start();
            t = new Thread(eventGroup::awaitTermination);
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

            assertTrue(System.currentTimeMillis() < start + TimeUnit.SECONDS.toMillis(5));

            for (int i = 0; i < 10; i++) {
                assertEquals(10, value.get());
                Jvm.pause(1);
            }
        }
        t.join(100);
        try {
            assertFalse(t.isAlive());
        } finally {
            t.interrupt();
        }
    }

    @Timeout(5)
    @Test
    public void testClosePausedBlockingEventLoop() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.addHandler(new PausingBlockingEventHandler());
        eventGroup.close();
        eventGroup.awaitTermination();
        assertTrue(eventGroup.isClosed());
        assertTrue(eventGroup.isStopped());
    }

    @Timeout(5)
    @Test
    public void testCloseAwaitTermination() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.close();
        eventGroup.awaitTermination();
        assertTrue(eventGroup.isClosed());
        assertTrue(eventGroup.isStopped());
    }

    @Timeout(5)
    @Test
    public void testCloseStopAwaitTermination() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.stop();
        eventGroup.close();
        eventGroup.awaitTermination();
        assertTrue(eventGroup.isClosed());
        assertTrue(eventGroup.isStopped());
    }

    @Timeout(5)
    @Test
    public void testCloseStopIdempotent() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.stop();
        eventGroup.stop();
        eventGroup.close();
        eventGroup.awaitTermination();
        assertTrue(eventGroup.isClosed());
        assertTrue(eventGroup.isStopped());
    }

    @Timeout(5)
    @Test
    public void testCloseAwaitTerminationWithoutStarting() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.close();
        eventGroup.awaitTermination();
        assertTrue(eventGroup.isClosed());
        assertTrue(eventGroup.isStopped());
    }

    @Timeout(5)
    @Test
    public void checkNoThreadsCreatedIfEventGroupNotStarted() {
        final ThreadDump threadDump = new ThreadDump();
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            threadDump.assertNoNewThreads();
        }
    }

    @Timeout(5)
    @Test
    public void checkAllEventHandlerTypesStartAndStop() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
        }
    }

    @Timeout(5)
    @Test
    public void checkNoThreadsAfterStopCalled() throws InterruptedException {
        final ThreadDump threadDump = new ThreadDump();
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
            eventGroup.stop();
            threadDump.assertNoNewThreads();
            handlers.forEach(testHandler -> assertNotEquals(0, testHandler.loopFinishedNS.get()));
        }
    }

    @Timeout(5)
    @Test
    public void checkHandlersNotClosedAfterStop() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
            eventGroup.stop();
            handlers.forEach(testHandler -> assertFalse(testHandler.isClosing()));
        }
    }

    @Timeout(5)
    @Test
    public void checkHandlersClosedImmediatelyOnInvalidHandlerException() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp, ExceptionType.INVALID_EVENT_HANDLER));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
            for (TestHandler handler : this.handlers)
                handler.assertClosed();
        }
    }

    @Timeout(5)
    @Test
    public void checkHandlersClosedImmediatelyOnRuntimeException() throws InterruptedException {
        expectException("el.exception.handler has been deprecated with no replacement");
        System.setProperty(ExceptionHandlerStrategy.IMPL_PROPERTY, ExceptionHandlerStrategy.LogAndRemove.class.getName());
        try {
            expectException(exceptionKey -> exceptionKey.throwable == RUNTIME_EXCEPTION, "message");
            try (final EventLoop eventGroup = EventGroup.builder().build()) {
                for (HandlerPriority hp : HandlerPriority.values())
                    eventGroup.addHandler(new EventGroupTest.TestHandler(hp, ExceptionType.RUNTIME));
                eventGroup.start();
                for (TestHandler handler : this.handlers)
                    handler.assertStarted();
                for (TestHandler handler : this.handlers)
                    handler.assertClosed();
            }
        } finally {
            System.clearProperty(ExceptionHandlerStrategy.IMPL_PROPERTY);
        }
    }

    @Timeout(5)
    @Test
    public void checkAllEventHandlerTypesStartAndStopAddAgain() throws InterruptedException {
        expectException("Only one high handler supported was TestHandler");
        try (final EventLoop eventGroup = EventGroup.builder().withName("test-eg").build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            for (TestHandler handler : this.handlers) {
                handler.assertInstalled();
                assertEquals(1, handler.started.getCount());
            }
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
            // add more after start
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
        }
    }

    @Timeout(5)
    @Test
    public void checkExecutedInOrderOfPriorityInline() throws InterruptedException {
        checkExecutedOrderOfPriority(HandlerPriority.MEDIUM, HandlerPriority.HIGH, HandlerPriority.MEDIUM);
    }

    @Timeout(5)
    @Test
    public void checkExecutedInOrderOfPriorityLoop() throws InterruptedException {
        checkExecutedOrderOfPriority(HandlerPriority.MEDIUM, HandlerPriority.MEDIUM, HandlerPriority.HIGH, HandlerPriority.MEDIUM, HandlerPriority.MEDIUM, HandlerPriority.MEDIUM);
    }

    private void checkExecutedOrderOfPriority(HandlerPriority... priorities) throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority priority : priorities)
                eventGroup.addHandler(new TestHandler(priority));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
            this.handlers.sort(Comparator.comparing(TestHandler::priority));
            long l0;
            long l1;
            do {
                Jvm.pause(1);
                l0 = this.handlers.get(0).firstActionNs.get();
                l1 = this.handlers.get(1).firstActionNs.get();
            } while (!Thread.currentThread().isInterrupted() && (l0 == 0 || l1 == 0));
            assertTrue(l0 < l1);
        }
        Jvm.pause(100);
        assertTrue(this.handlers.get(0).actionCalled.get() > this.handlers.get(1).actionCalled.get());
    }

    @Timeout(5)
    @Test
    public void checkAllEventHandlerTypesStartInvalidEventHandlerException() throws InterruptedException {
        checkException(ExceptionType.INVALID_EVENT_HANDLER);
    }

    @Timeout(5)
    @Test
    public void checkAllEventHandlerTypesStartRuntimeException() throws InterruptedException {
        expectException("el.exception.handler has been deprecated with no replacement");
        try {
            System.setProperty(ExceptionHandlerStrategy.IMPL_PROPERTY, ExceptionHandlerStrategy.LogAndRemove.class.getName());
            expectException(exceptionKey -> exceptionKey.throwable == RUNTIME_EXCEPTION, "message");
            checkException(ExceptionType.RUNTIME);
        } finally {
            System.clearProperty(ExceptionHandlerStrategy.IMPL_PROPERTY);
        }
    }

    private void checkException(ExceptionType exceptionType) throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build();) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new TestHandler(hp, exceptionType));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
            Jvm.pause(100);
        }
        for (TestHandler handler : this.handlers) {
            assertEquals(1, handler.actionCalled.get(), "expected called once only " + handler);
        }
    }

    // TODO: checkAllEventHandlerTypesContinueRuntimeException()

    @Timeout(5)
    @Test
    public void testCloseAddHandler() {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            eventGroup.close();
            for (HandlerPriority hp : HandlerPriority.values()) {
                final TestHandler handler = new TestHandler(hp);
                try {
                    eventGroup.addHandler(handler);
                    fail("Should have failed " + handler);
                } catch (IllegalStateException e) {
                    // this is what we want
                }
            }
            handlers.clear();
        }
    }

    @Timeout(5)
    @Test
    public void testEventGroupNoCoreEventLoop() {
        final AtomicReference<EventLoop> ref = new AtomicReference<>();
        try (EventLoop eg = EventGroup.builder()
                .withConcurrentThreadsNum(0)
                .withPriorities(HandlerPriority.REPLICATION)
                .build()) {
            ref.set(eg);
            eg.unpause();
        }
        assertTrue(ref.get().isClosed());
    }

    @Test
    public void inEventLoop() {
        try (EventGroup eg = EventGroup.builder().build()) {
            eg.start();
            assertFalse(EventLoop.inEventLoop());
            Set<HandlerPriority> priorities = new ConcurrentSkipListSet<>();
            for (HandlerPriority priority : HandlerPriority.values()) {
                eg.addHandler(new EventHandler() {
                    @Override
                    public boolean action() throws InvalidEventHandlerException {
                        try {
                            assertTrue(EventLoop.inEventLoop(), priority.name());
                            priorities.add(priority);
                        } catch (Throwable t) {
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
    public void handlersWithASharedResourceShutdownGracefully() {
        EventGroup eventGroup = EventGroup.builder().build();
        CloseableResource resource = new CloseableResource();
        for (HandlerPriority handlerPriority : HandlerPriority.values()) {
            IntStream.of(4).forEach(i -> eventGroup.addHandler(new SharedResourceUsingHandler(resource, handlerPriority)));
        }
        eventGroup.start();
        Jvm.pause(1000);
        eventGroup.close();
        assertTrue(resource.isClosed());
    }

    @Test
    void daemonParameterShouldBeUsedWhenCreatingReplicationEventLoop() throws IllegalAccessException {
        try (final EventGroup eventGroup = EventGroup.builder()
                .withDaemon(false)
                .withPriorities(HandlerPriority.REPLICATION)
                .build()) {
            eventGroup.addHandler(new TestHandler(HandlerPriority.REPLICATION));  // replication EventLoop is lazily created
            final MediumEventLoop replication = (MediumEventLoop) Jvm.getField(EventGroup.class, "replication").get(eventGroup);
            assertFalse(replication.daemon);
        }
    }

    @Test
    void lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads() {
        lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads_ForPriorities(Arrays.stream(HandlerPriority.values()).collect(Collectors.toSet()));
        // You get a MediumEventLoop instead of a VanillaEventLoop when you only have medium priority
        lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads_ForPriorities(singleton(HandlerPriority.MEDIUM));
    }

    void lifecycleEventsAreCalledAtAppropriateTimesByAppropriateThreads_ForPriorities(Set<HandlerPriority> priorities) {
        handlers.clear();
        EventGroup eventGroup = EventGroup.builder().withPriorities(priorities).build();
        for (HandlerPriority handlerPriority : priorities) {
            final TestHandler handler = new TestHandler(handlerPriority);
            eventGroup.addHandler(handler);
        }
        handlers.forEach(handler -> assertEquals(handler.loopStartedNS.get(), 0, handler.priority + " was loopStarted before loop started, priorities=" + priorities));
        eventGroup.start();
        handlers.forEach(handler -> assertEquals(handler.loopFinishedNS.get(), 0, handler.priority + " was loopFinished before loop finished, priorities=" + priorities));
        Jvm.pause(1000);
        handlers.forEach(handler -> assertNotEquals(handler.loopStartedNS.get(), 0, handler.priority + " was not loopStarted when loop started, priorities=" + priorities));
        eventGroup.close();
        handlers.forEach(handler -> assertNotEquals(handler.loopFinishedNS.get(), 0, handler.priority + " was not loopFinished when loop finished, priorities=" + priorities));
    }

    static class CloseableResource extends AbstractCloseable {

        public CloseableResource() {
            singleThreadedCheckDisabled(true);
        }

        @Override
        protected void performClose() throws IllegalStateException {
            Jvm.startup().on(CloseableResource.class, "Being closed!");
        }

        public void use() {
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

    class TestHandler extends SimpleCloseable implements EventHandler, Closeable {
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

        TestHandler(HandlerPriority priority) {
            this(priority, ExceptionType.NONE);
        }

        TestHandler(HandlerPriority priority, ExceptionType exceptionType) {
            this.priority = priority;
            this.exceptionType = exceptionType;
            handlers.add(this);
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            // // System.out.println("action " + priority + " " + super.toString());
            assertEquals(0, installed.getCount(), "eventLoop must be called before the first " +
                    "action call (priority=" + priority + " )");

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
            assertTrue(loopStartedNS.compareAndSet(0, System.nanoTime()), "loopStarted should only ever be called once");
            started.countDown();
            assertTrue(EventLoop.inEventLoop(), "loopStarted should be called on EL thread (called on `"
                    + Thread.currentThread().getName()
                    + "`, priority=" + priority + " )");
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
            assertTrue(loopFinishedNS.compareAndSet(0, System.nanoTime()), "loopFinished called once only " + this);
            Jvm.busyWaitMicros(1);
        }

        @Override
        protected void performClose() {
            super.performClose();

            // // System.out.println("closed " + this);
            closed.countDown();
            assertTrue(closedNS.compareAndSet(0, System.nanoTime()), "close should be called once only " + this);
        }

        public void assertStarted() throws InterruptedException {
            assertTrue(started.await(1000, TimeUnit.MILLISECONDS), String.format("Handler with priority %s was never started", priority));
        }

        public void assertInstalled() throws InterruptedException {
            assertTrue(installed.await(100, TimeUnit.MILLISECONDS), String.format("Handler with priority %s was never installed", priority));
        }

        public void assertClosed() throws InterruptedException {
            assertTrue(closed.await(100, TimeUnit.MILLISECONDS), String.format("Handler with priority %s was never closed", priority));
        }

        void checkCloseOrder() {
            assertNotEquals(0, loopFinishedNS.get(), this.toString());
            assertNotEquals(0, closedNS.get(), this.toString());
            assertTrue(loopFinishedNS.get() < closedNS.get(), this.toString());
        }

        @Override
        public String toString() {
            return "TestHandler{" +
                    "priority=" + priority +
                    ", loopFinishedNS=" + loopFinishedNS +
                    ", closedNS=" + closedNS +
                    ", started=" + started.getCount() +
                    '}';
        }
    }
}
