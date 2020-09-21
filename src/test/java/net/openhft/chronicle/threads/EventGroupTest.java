/*
 * Copyright 2016-2020 Chronicle Software
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
import net.openhft.chronicle.core.threads.*;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertFalse;

public class EventGroupTest extends ThreadsTestCommon {
    private List<TestHandler> handlers;

    @Before
    public void handlersInit() {
        handlers = new ArrayList<>();
    }

    @After
    public void checkHandlersClosed() {
        handlers.forEach(TestHandler::checkCloseOrder);
    }

    @Test(timeout = 5000)
    public void testSimpleEventGroupTest() throws InterruptedException {

        final AtomicInteger value = new AtomicInteger();

        Thread t;
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "testSimpleEventGroupTest",
                EventGroup.CONC_THREADS, EnumSet.of(HandlerPriority.MEDIUM))) {
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
        assertFalse(t.isAlive());
    }

    @Test(timeout = 5000)
    public void testClosePausedBlockingEventLoop() {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.start();
        eventGroup.addHandler(new PausingBlockingEventHandler());
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void testCloseAwaitTermination() {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.start();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void testCloseStopAwaitTermination() {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.start();
        eventGroup.stop();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void testCloseAwaitTerminationWithoutStarting() {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 5000)
    public void checkNoThreadsCreatedIfEventGroupNotStarted() {
        final ThreadDump threadDump = new ThreadDump();
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "", EventGroup.CONC_THREADS, EnumSet.allOf(HandlerPriority.class))) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            threadDump.assertNoNewThreads();
        }
    }

    @Test(timeout = 5000)
    public void checkAllEventHandlerTypesStartAndStop() throws InterruptedException {
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "", EventGroup.CONC_THREADS, EnumSet.allOf(HandlerPriority.class))) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.started.await(100, TimeUnit.MILLISECONDS);
        }
    }

    @Test(timeout = 5000)
    public void checkAllEventHandlerTypesStartAndStopAddAgain() throws InterruptedException {
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "", EventGroup.CONC_THREADS, EnumSet.allOf(HandlerPriority.class))) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            for (TestHandler handler : this.handlers) {
                handler.installed.await(100, TimeUnit.MILLISECONDS);
                Assert.assertEquals(1, handler.started.getCount());
            }
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.started.await(100, TimeUnit.MILLISECONDS);
            // add more after start
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            for (TestHandler handler : this.handlers)
                handler.started.await(100, TimeUnit.MILLISECONDS);
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
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "", EventGroup.CONC_THREADS, EnumSet.allOf(HandlerPriority.class))) {
            for (HandlerPriority priority : priorities)
                eventGroup.addHandler(new TestHandler(priority));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.started.await(100, TimeUnit.MILLISECONDS);
            this.handlers.sort(Comparator.comparing(TestHandler::priority));
            Assert.assertTrue(this.handlers.get(0).firstActionNs.get() < this.handlers.get(1).firstActionNs.get());
        }
    }

    @Test(timeout = 5000)
    public void checkAllEventHandlerTypesStartInvalidEventHandlerException() throws InterruptedException {
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "", EventGroup.CONC_THREADS, EnumSet.allOf(HandlerPriority.class))) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp, true));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.started.await(100, TimeUnit.MILLISECONDS);
            Jvm.pause(100);
        }
    }

    @Test(timeout = 5000)
    public void testCloseAddHandler() {
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "", EventGroup.CONC_THREADS, EnumSet.allOf(HandlerPriority.class))) {
            eventGroup.close();
            for (HandlerPriority hp : HandlerPriority.values())
                try {
                    TestHandler handler = new TestHandler(hp);
                    eventGroup.addHandler(handler);
                    Assert.fail("Should have failed " + handler);
                } catch (IllegalStateException e) {
                    // this is what we want
                }
            handlers.clear();
        }
    }

    @Test
    public void testEventGroupNoCoreEventLoop() {
        try (EventLoop eg = new EventGroup(true, Pauser.balanced(), "none", "none", "", 0, EnumSet.of(HandlerPriority.REPLICATION))) {
            eg.unpause();
        }
    }

    @Test(timeout = 5000)
    public void testOldOverloadUnsupported() {
        try (final EventLoop eventGroup = new EventGroup(true, Pauser.balanced(), "none", "none", "", EventGroup.CONC_THREADS, EnumSet.allOf(HandlerPriority.class))) {
            eventGroup.close();
            for (HandlerPriority hp : HandlerPriority.values())
                try {
                    TestHandler handler = new TestHandler(hp);
                    eventGroup.addHandler(true, handler);
                    Assert.fail("Should have failed " + handler);
                } catch (UnsupportedOperationException e) {
                    // this is what we want
                }
            handlers.clear();
        }
    }

    class TestHandler implements EventHandler, Closeable {
        final CountDownLatch installed = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicLong loopFinishedNS = new AtomicLong();
        final AtomicLong closedNS = new AtomicLong();
        final AtomicLong firstActionNs = new AtomicLong();
        final HandlerPriority priority;
        final boolean throwInvalidEventHandlerException;

        TestHandler(HandlerPriority priority) {
            this(priority, false);
        }

        TestHandler(HandlerPriority priority, boolean throwInvalidEventHandlerException) {
            this.priority = priority;
            this.throwInvalidEventHandlerException = throwInvalidEventHandlerException;
            handlers.add(this);
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            started.countDown();
            if (throwInvalidEventHandlerException)
                throw new InvalidEventHandlerException();
            if (priority == HandlerPriority.BLOCKING)
                LockSupport.park();
            this.firstActionNs.compareAndSet(0, System.nanoTime());
            Jvm.pause(1);
            return false;
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
        public void close() {
            Assert.assertTrue("close called once only " + this, closedNS.compareAndSet(0, System.nanoTime()));
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

    private class PausingBlockingEventHandler implements EventHandler {
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
}
