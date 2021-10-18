/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

public class EventGroupTest extends ThreadsTestCommon {
    private static final RuntimeException RUNTIME_EXCEPTION = new RuntimeException("some random text");
    private final List<TestHandler> handlers = new ArrayList<>();

    @Before
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

    @Test(timeout = 5000)
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
            while (value.get() != 10) {
                Jvm.pause(10);
            }

            Assert.assertTrue(System.currentTimeMillis() < start + TimeUnit.SECONDS.toMillis(5));

            for (int i = 0; i < 10; i++) {
                Assert.assertEquals(10, value.get());
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

    @Test(timeout = 5000)
    public void testClosePausedBlockingEventLoop() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.addHandler(new PausingBlockingEventHandler());
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void testCloseAwaitTermination() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void testCloseStopAwaitTermination() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.stop();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void testCloseStopIdempotent() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.start();
        eventGroup.stop();
        eventGroup.stop();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void testCloseAwaitTerminationWithoutStarting() {
        final EventLoop eventGroup = EventGroup.builder().build();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void checkNoThreadsCreatedIfEventGroupNotStarted() {
        final ThreadDump threadDump = new ThreadDump();
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            threadDump.assertNoNewThreads();
        }
    }

    @Test(timeout = 5000)
    public void checkAllEventHandlerTypesStartAndStop() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
        }
    }

    @Test(timeout = 5000)
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
            handlers.forEach(testHandler -> Assert.assertTrue(testHandler.loopFinishedNS.get() != 0));
        }
    }

    @Test(timeout = 5000)
    public void checkHandlersNotClosedAfterStop() throws InterruptedException {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.assertStarted();
            eventGroup.stop();
            handlers.forEach(testHandler -> Assert.assertFalse(testHandler.isClosing()));
        }
    }

    @Test(timeout = 5000)
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

    @Test(timeout = 5000)
    public void checkHandlersClosedImmediatelyOnRuntimeException() throws InterruptedException {
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

    @Test(timeout = 5000)
    public void checkAllEventHandlerTypesStartAndStopAddAgain() throws InterruptedException {
        expectException("Only one high handler supported was TestHandler");
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            for (TestHandler handler : this.handlers) {
                handler.assertInstalled();
                Assert.assertEquals(1, handler.started.getCount());
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

    @Test(timeout = 5000)
    public void checkExecutedInOrderOfPriorityInline() throws InterruptedException {
        checkExecutedOrderOfPriority(HandlerPriority.MEDIUM, HandlerPriority.HIGH, HandlerPriority.MEDIUM);
    }

    @Test(timeout = 5000)
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
            } while (l0 == 0 || l1 == 0);
            Assert.assertTrue(l0 < l1);
        }
        Jvm.pause(100);
        Assert.assertTrue(this.handlers.get(0).actionCalled.get() > this.handlers.get(1).actionCalled.get());
    }

    @Test(timeout = 5000)
    public void checkAllEventHandlerTypesStartInvalidEventHandlerException() throws InterruptedException {
        checkException(ExceptionType.INVALID_EVENT_HANDLER);
    }

    @Test(timeout = 5000)
    public void checkAllEventHandlerTypesStartRuntimeException() throws InterruptedException {
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
            Assert.assertEquals("expected called once only " + handler, 1, handler.actionCalled.get());
        }
    }

    // TODO: checkAllEventHandlerTypesContinueRuntimeException()

    @Test(timeout = 5000)
    public void testCloseAddHandler() {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            eventGroup.close();
            for (HandlerPriority hp : HandlerPriority.values())
                try {
                    TestHandler handler = new TestHandler(hp);
                    eventGroup.addHandler(handler);
                    fail("Should have failed " + handler);
                } catch (IllegalStateException e) {
                    // this is what we want
                }
            handlers.clear();
        }
    }

    @Test(timeout = 5000)
    public void testEventGroupNoCoreEventLoop() {
        try (EventLoop eg = EventGroup.builder()
                .withConcurrentThreadsNum(0)
                .withPriorities(HandlerPriority.REPLICATION)
                .build()) {
            eg.unpause();
        }
    }

    @Test
    public void inEventLoop() {
        EventGroup eg = EventGroup.builder().build();
        eg.start();
        assertFalse(EventLoop.inEventLoop());
        Set<HandlerPriority> priorities = new ConcurrentSkipListSet<>();
        for (HandlerPriority priority : HandlerPriority.values()) {
            eg.addHandler(new EventHandler() {
                @Override
                public boolean action() throws InvalidEventHandlerException {
                    try {
                        assertTrue(priority.name(), EventLoop.inEventLoop());
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
        eg.stop();
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

    static class CloseableResource extends AbstractCloseable {

        public CloseableResource() {
            disableThreadSafetyCheck(true);
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
            started.countDown();
            Assert.assertTrue("loopStarted should be called on EL thread (called on `"
                    + Thread.currentThread().getName()
                    + "`, priority=" + priority + " )", EventLoop.inEventLoop());
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
            Assert.assertTrue("loopFinished called once only " + this, loopFinishedNS.compareAndSet(0, System.nanoTime()));
            Jvm.busyWaitMicros(1);
        }

        @Override
        protected void performClose() {
            super.performClose();

            // // System.out.println("closed " + this);
            closed.countDown();
            Assert.assertTrue("close should be called once only " + this, closedNS.compareAndSet(0, System.nanoTime()));
        }

        public void assertStarted() throws InterruptedException {
            assertTrue(String.format("Handler with priority %s was never started", priority), started.await(1000, TimeUnit.MILLISECONDS));
        }

        public void assertInstalled() throws InterruptedException {
            assertTrue(String.format("Handler with priority %s was never installed", priority), installed.await(100, TimeUnit.MILLISECONDS));
        }

        public void assertClosed() throws InterruptedException {
            assertTrue(String.format("Handler with priority %s was never closed", priority), closed.await(100, TimeUnit.MILLISECONDS));
        }

        void checkCloseOrder() {
            Assert.assertTrue(this.toString(), loopFinishedNS.get() != 0);
            Assert.assertTrue(this.toString(), closedNS.get() != 0);
            Assert.assertTrue(this.toString(), loopFinishedNS.get() < closedNS.get());
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
