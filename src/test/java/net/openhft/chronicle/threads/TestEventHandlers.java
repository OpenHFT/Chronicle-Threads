package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class TestEventHandlers {

    public static class CountingHandler implements EventHandler, Closeable {
        protected final AtomicInteger loopStartedCalled = new AtomicInteger();
        protected final AtomicInteger loopFinishedCalled = new AtomicInteger();
        protected final AtomicInteger actionCalled = new AtomicInteger();
        protected final AtomicInteger closeCalled = new AtomicInteger();
        protected final HandlerPriority priority;
        protected EventLoop eventLoop;

        CountingHandler(HandlerPriority priority) {
            this.priority = priority;
        }

        @Override
        public void eventLoop(EventLoop eventLoop) {
            this.eventLoop = eventLoop;
        }

        public EventLoop eventLoop() {
            return eventLoop;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return priority;
        }

        @Override
        public void loopStarted() {
            loopStartedCalled.incrementAndGet();
        }

        public int loopStartedCalled() {
            return loopStartedCalled.get();
        }

        @Override
        public boolean action() {
            actionCalled.incrementAndGet();
            return false;
        }

        public int actionCalled() {
            return actionCalled.get();
        }

        @Override
        public void loopFinished() {
            loopFinishedCalled.incrementAndGet();
        }

        public int loopFinishedCalled() {
            return loopFinishedCalled.get();
        }

        @Override
        public void close() throws IOException {
            closeCalled.incrementAndGet();
        }

        public int closeCalled() {
            return closeCalled.get();
        }
    }

    public static final String HANDLER_LOOP_STARTED_EXCEPTION_TXT = "Something went wrong in loopStarted!!!";
    public static final String HANDLER_LOOP_FINISHED_EXCEPTION_TXT = "Something went wrong in loopFinished!!!";
    public static final String HANDLER_CLOSE_EXCEPTION_TXT = "Something went wrong in close!!!";
    public static final String HANDLER_EVENT_LOOP_EXCEPTION_TXT = "Something went wrong in set eventLoop!!!";
    public static final String HANDLER_PRIORITY_EXCEPTION_TXT = "Something went wrong in priority!!!";

    public static class ThrowingHandler extends CountingHandler {
        protected final boolean throwsEventLoop;
        protected final boolean throwsPriority;
        protected final boolean throwsLoopStarted;
        protected final boolean throwsLoopFinished;
        protected final boolean throwsClose;

        ThrowingHandler(HandlerPriority priority, boolean throwsEventLoop, boolean throwsPriority) {
            super(priority);
            this.throwsEventLoop = throwsEventLoop;
            this.throwsPriority = throwsPriority;
            if (throwsEventLoop || throwsPriority) {
                throwsLoopStarted = false;
                throwsLoopFinished = false;
                throwsClose = false;
            } else {
                throwsLoopStarted = true;
                throwsLoopFinished = true;
                throwsClose = true;
            }
        }

        @Override
        public void eventLoop(EventLoop eventLoop) {
            super.eventLoop(eventLoop);
            if (throwsEventLoop) {
                throw new IllegalStateException(HANDLER_EVENT_LOOP_EXCEPTION_TXT + priority);
            }
        }

        @Override
        public void loopStarted() {
            super.loopStarted();
            if (throwsLoopStarted) {
                throw new IllegalStateException(HANDLER_LOOP_STARTED_EXCEPTION_TXT + priority);
            }
        }

        @Override
        public void loopFinished() {
            super.loopFinished();
            if (throwsLoopFinished) {
                throw new IllegalStateException(HANDLER_LOOP_FINISHED_EXCEPTION_TXT + priority);
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (throwsClose) {
                throw new IllegalStateException(HANDLER_CLOSE_EXCEPTION_TXT + priority);
            }
        }

        @Override
        public @NotNull HandlerPriority priority() {
            HandlerPriority result = super.priority();
            if (throwsPriority) {
                throw new IllegalStateException(HANDLER_PRIORITY_EXCEPTION_TXT + priority);
            }
            return result;
        }
    }
}
