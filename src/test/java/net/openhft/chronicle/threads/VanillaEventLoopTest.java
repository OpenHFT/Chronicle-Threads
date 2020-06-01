package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class VanillaEventLoopTest {

    private static final int LOOPS = 2;

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
    public void testEnsureRemoveInvokesLoopFinishedJustOnce() {
        final VanillaEventLoop el = new VanillaEventLoop(null, "test-event-loop", PauserMode.busy.get(), 20, false, "none", EnumSet.of(HandlerPriority.MEDIUM));

        final TestMediumEventHandler eh0 = new TestMediumEventHandler();
        final TestMediumEventHandler eh1 = new TestMediumEventHandler();

        el.addHandler(eh0);
        el.addHandler(eh1);

        el.start();

        Jvm.pause(500);

        System.out.println(eh0);
        System.out.println(eh1);

        el.stop();

        assertEquals(LOOPS, eh0.actionCnt);
        assertEquals(LOOPS, eh1.actionCnt);

        assertEquals(1, eh0.finishedCnt);
        assertEquals(1, eh1.finishedCnt);

    }

    private static final class TestMediumEventHandler implements EventHandler, Closeable {

        private int actionCnt;
        private int finishedCnt;

        @Override
        public boolean action() throws InvalidEventHandlerException {
            if (actionCnt >= LOOPS)
                throw InvalidEventHandlerException.reusable();
            actionCnt++;
            return false;
        }

        @Override
        public void loopFinished() {
            finishedCnt++;
        }

        @Override
        public void close() {
            throwExceptionIfClosed();
            finishedCnt++;
        }

        @Override
        public boolean isClosed() {
            return finishedCnt > 0;
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