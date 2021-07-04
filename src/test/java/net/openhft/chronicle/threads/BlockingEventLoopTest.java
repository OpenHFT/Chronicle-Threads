package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class BlockingEventLoopTest extends ThreadsTestCommon {

    private BlockingEventLoop blockingEventLoop;

    @Before
    public void setUp() {
        blockingEventLoop = new BlockingEventLoop("test-loop");
    }

    @After
    public void tearDown() {
        blockingEventLoop.close();
        blockingEventLoop.awaitTermination();
    }

    @Test
    public void testStopWillTerminateLoop() {
        final BlockingTask handler = new BlockingTask();
        blockingEventLoop.addHandler(handler);
        blockingEventLoop.start();
        Jvm.pause(100);

        long startTime = System.currentTimeMillis();
        blockingEventLoop.stop();
        blockingEventLoop.awaitTermination();
        assertTrue(System.currentTimeMillis() - startTime < 100);
        handler.assertActionWasCalled();
        handler.assertLoopFinishedCalledOnce();
    }

    @Test
    public void testCloseWillTerminateLoop() {
        final BlockingTask handler = new BlockingTask();
        blockingEventLoop.addHandler(handler);
        blockingEventLoop.start();
        Jvm.pause(100);

        long startTime = System.currentTimeMillis();
        blockingEventLoop.close();
        blockingEventLoop.awaitTermination();
        assertTrue(System.currentTimeMillis() - startTime < 100);
        handler.assertActionWasCalled();
        handler.assertLoopFinishedCalledOnce();
        handler.assertCloseWasCalled();
    }

    @Test
    public void testHandlerThatThrowsWillBeRemovedAndClosed() throws TimeoutException {
        expectException("Boom!");
        final BlockingTask handler = new BlockingTask(300);
        blockingEventLoop.addHandler(handler);
        blockingEventLoop.start();

        long actionFirstCalled = handler.waitForFirstCall();

        handler.throwNextCall();
        handler.waitForLoopToEnd();

        assertEquals(actionFirstCalled, handler.actionLastCalled);
        handler.assertCloseWasCalled();
        handler.assertLoopFinishedCalledOnce();
    }

    @Test
    public void testHandlerThatThrowsInvalidHandlerWillBeSilentlyRemovedAndClosed() throws TimeoutException {
        final BlockingTask handler = new BlockingTask(300);
        blockingEventLoop.addHandler(handler);
        blockingEventLoop.start();

        long actionFirstCalled = handler.waitForFirstCall();

        handler.endNextCall();
        handler.waitForLoopToEnd();

        assertEquals(actionFirstCalled, handler.actionLastCalled);
        handler.assertCloseWasCalled();
        handler.assertLoopFinishedCalledOnce();
    }

    @Test
    public void testStartWillThrowAfterClose() {
        blockingEventLoop.close();
        try {
            blockingEventLoop.start();
            fail();
        } catch (IllegalStateException expected) {
            // Expected
        }
    }

    @Test
    public void testAddingHandlerToStoppedEventLoopWillThrow() {
        blockingEventLoop.stop();

        try {
            blockingEventLoop.addHandler(new BlockingTask());
            fail();
        } catch (IllegalStateException expected) {
            // Expected
        }
    }

    @Test
    public void testHandlerIsNotExecutedWhenEventLoopIsNotStarted() {
        final BlockingTask handler = new BlockingTask();
        blockingEventLoop.addHandler(handler);
        Jvm.pause(100);
        handler.assertActionWasNotCalled();
    }

    @Test
    public void testHandlerIsExecutedImmediatelyWhenLoopIsStarted() {
        blockingEventLoop.start();
        final BlockingTask handler = new BlockingTask();
        blockingEventLoop.addHandler(handler);
        Jvm.pause(10);
        handler.assertActionWasCalled();
    }

    static class BlockingTask implements EventHandler, Closeable {

        private final AtomicInteger loopFinishedCalledTimes = new AtomicInteger(0);
        private final long pauseTime;
        private volatile boolean closeCalled = false;
        private volatile long actionLastCalled = Long.MIN_VALUE;
        private volatile boolean throwNextCall = false;
        private volatile boolean endNextCall = false;

        public BlockingTask() {
            // Ten seconds by default
            this(10_000);
        }

        public BlockingTask(long pauseTime) {
            this.pauseTime = pauseTime;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            if (endNextCall) {
                throw InvalidEventHandlerException.reusable();
            }
            if (throwNextCall) {
                throw new IllegalStateException("Boom!");
            }
            actionLastCalled = System.nanoTime();
            Jvm.pause(pauseTime);
            return false;
        }

        @Override
        public void loopFinished() {
            loopFinishedCalledTimes.incrementAndGet();
        }

        public void assertLoopFinishedCalledOnce() {
            assertEquals("Loop finished was not called exactly once", 1, loopFinishedCalledTimes.get());
        }

        public void assertCloseWasCalled() {
            assertTrue("Close was not called!", closeCalled);
        }

        public void assertActionWasCalled() {
            assertTrue("Action was not called!", actionLastCalled != Long.MIN_VALUE);
        }

        public void assertActionWasNotCalled() {
            assertTrue("Action has been called!", actionLastCalled == Long.MIN_VALUE);
        }

        public void throwNextCall() {
            throwNextCall = true;
        }

        public void endNextCall() {
            endNextCall = true;
        }

        public long waitForFirstCall() throws TimeoutException {
            TimingPauser pauser = Pauser.balanced();
            long firstCallNanoTimestamp;
            while ((firstCallNanoTimestamp = actionLastCalled) == Long.MIN_VALUE) {
                pauser.pause(1, TimeUnit.SECONDS);
            }
            return firstCallNanoTimestamp;
        }

        public void waitForLoopToEnd() throws TimeoutException {
            TimingPauser pauser = Pauser.balanced();
            while (loopFinishedCalledTimes.get() == 0) {
                pauser.pause(1, TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() {
            closeCalled = true;
        }
    }
}