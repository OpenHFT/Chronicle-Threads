package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.FlakyTestRunner;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.SimpleCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.*;

public class VanillaEventLoopTest extends ThreadsTestCommon {

    private static final int LOOPS = 13;

    @Test
    public void testActionIsDoneLoopTimes() throws InvalidEventHandlerException {
        final TestMediumEventHandler eh0 = new TestMediumEventHandler();
        for (int i = 0; i < LOOPS; i++) {
            eh0.action();
        }
        try {
            eh0.action();
            fail("");
        } catch (InvalidEventHandlerException ignore) {

        }
        assertEquals(LOOPS, eh0.actionCnt);
    }

    @Test(timeout = 10_000L)
    public void testEnsureRemoveInvokesLoopFinishedJustOnce() throws InterruptedException {
        FlakyTestRunner.run(this::testEnsureRemoveInvokesLoopFinishedJustOnce0);
    }

    public void testEnsureRemoveInvokesLoopFinishedJustOnce0() throws InterruptedException {
        final VanillaEventLoop el = new VanillaEventLoop(null, "test-event-loop", PauserMode.busy.get(), 20, false, "none", EnumSet.of(HandlerPriority.MEDIUM));

        final TestMediumEventHandler eh0 = new TestMediumEventHandler();
        final TestMediumEventHandler eh1 = new TestMediumEventHandler();

        el.addHandler(eh0);
        el.addHandler(eh1);

        el.start();

        for (int i = 100; i >= 0; i--) {
            if (el.thread() != null)
                break;
            Jvm.pause(50);
            assertNotEquals("thread failed to start", 0, i);
        }

        System.out.println(eh0);
        System.out.println(eh1);

        Thread thread = el.thread();
        if (thread == null)
            return;
        el.stop();

        thread.join(1000);

        assertEquals(LOOPS, eh0.actionCnt);
        assertEquals(LOOPS, eh1.actionCnt);

        assertEquals(1, eh0.finishedCnt);
        assertEquals(1, eh1.finishedCnt);
        el.close();

    }

    private static final class TestMediumEventHandler extends SimpleCloseable implements EventHandler {

        private volatile int actionCnt;
        private volatile int finishedCnt;

        @Override
        public boolean action() throws InvalidEventHandlerException {
            if (actionCnt >= LOOPS)
                throw InvalidEventHandlerException.reusable();
            actionCnt++;
            return false;
        }

        @Override
        protected void performClose() {
            super.performClose();
            finishedCnt++;
        }

        @NotNull
        @Override
        public HandlerPriority priority() {
            return HandlerPriority.MEDIUM;
        }

        @Override
        public String toString() {
            return "TestMediumEventHandler{" +
                    "actionCnt=" + actionCnt +
                    ", finishedCnt=" + finishedCnt +
                    '}';
        }
    }

}