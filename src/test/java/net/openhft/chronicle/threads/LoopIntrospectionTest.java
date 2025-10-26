/*
 * Copyright 2016-2025 chronicle.software
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
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.testframework.Waiters;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import net.openhft.chronicle.threads.TestEventHandlers.CountingHandler;

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoopIntrospectionTest extends ThreadsTestCommon {

    @Test
    void mediumEventLoopReportsRunningThread() throws InterruptedException {
        AtomicReference<Thread> loopThread = new AtomicReference<>();
        CountDownLatch firstInvocation = new CountDownLatch(1);

        try (MediumEventLoop loop = new MediumEventLoop(null, "introspection-medium",
                Pauser.balanced(), true, null)) {
            loop.start();
            Waiters.waitForCondition("Medium loop did not start", loop::isStarted, 5_000);

            loop.addHandler(new EventHandler() {
                @Override
                public @NotNull HandlerPriority priority() {
                    return HandlerPriority.MEDIUM;
                }

                @Override
                public boolean action() {
                    loopThread.compareAndSet(null, Thread.currentThread());
                    firstInvocation.countDown();
                    return false;
                }
            });

            assertTrue(firstInvocation.await(5, TimeUnit.SECONDS), "Handler never ran on medium loop");
            Thread executing = loopThread.get();
            assertNotNull(executing, "Medium loop thread was not captured");

            assertTrue(loop.isRunningOnThread(executing), "Loop failed to recognise its worker thread");
            assertFalse(loop.isRunningOnThread(new Thread()), "Loop incorrectly matched unrelated thread");
        }
    }

    @Test
    void blockingEventLoopReportsRunningThread() throws InterruptedException {
        AtomicReference<Thread> loopThread = new AtomicReference<>();
        CountDownLatch firstInvocation = new CountDownLatch(1);

        try (BlockingEventLoop loop = new BlockingEventLoop("introspection-blocking")) {
            loop.start();

            loop.addHandler(() -> {
                loopThread.compareAndSet(null, Thread.currentThread());
                firstInvocation.countDown();
                Jvm.pause(10);
                return false;
            });

            assertTrue(firstInvocation.await(5, TimeUnit.SECONDS), "Handler never ran on blocking loop");
            Thread executing = loopThread.get();
            assertNotNull(executing, "Blocking loop thread was not captured");

            assertTrue(loop.isRunningOnThread(executing), "Blocking loop failed to recognise its worker thread");
            assertFalse(loop.isRunningOnThread(Thread.currentThread()), "Blocking loop matched caller thread");
        }
    }

    @Test
    void eventGroupAggregatesRunningThreadChecks() throws InterruptedException {
        AtomicReference<Thread> highThread = new AtomicReference<>();
        AtomicReference<Thread> blockingThread = new AtomicReference<>();
        AtomicReference<Thread> monitorThread = new AtomicReference<>();

        int previousDelay = MonitorEventLoop.MONITOR_INITIAL_DELAY_MS;
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = 1;
        try (EventGroup group = EventGroup.builder()
                .withPriorities(EnumSet.of(HandlerPriority.HIGH, HandlerPriority.BLOCKING, HandlerPriority.MONITOR))
                .withPauser(Pauser.balanced())
                .build()) {
            group.start();
            Waiters.waitForCondition("Event group did not start", group::isStarted, 5_000);

            group.addHandler(new RecordingHandler(HandlerPriority.HIGH, highThread));
            group.addHandler(new RecordingHandler(HandlerPriority.BLOCKING, blockingThread));
            group.addHandler(new RecordingHandler(HandlerPriority.MONITOR, monitorThread));

            Waiters.waitForCondition("High loop thread not captured", () -> highThread.get() != null, 5_000);
            Waiters.waitForCondition("Blocking loop thread not captured", () -> blockingThread.get() != null, 5_000);
            Waiters.waitForCondition("Monitor loop thread not captured", () -> monitorThread.get() != null, 5_000);

            assertTrue(group.isRunningOnThread(highThread.get()), "Group did not recognise high-priority loop thread");
            assertTrue(group.isRunningOnThread(blockingThread.get()), "Group did not recognise blocking loop thread");
            assertTrue(group.isRunningOnThread(monitorThread.get()), "Group did not recognise monitor loop thread");
            assertFalse(group.isRunningOnThread(new Thread()), "Group matched unrelated thread");
        } finally {
            MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = previousDelay;
        }
    }

    @Test
    void mediumEventLoopClosesPendingHandlersOnClose() {
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);

        try (MediumEventLoop loop = new MediumEventLoop(null, "pending-medium",
                Pauser.balanced(), true, null)) {
            loop.addHandler(handler);
            assertEquals(0, handler.loopStartedCalled(), "Handler should not start before loop runs");
        }

        assertEquals(0, handler.loopStartedCalled(), "loopStarted should not be called");
        assertEquals(0, handler.actionCalled(), "action should not be called");
        assertEquals(1, handler.closeCalled(), "Handler should be closed when loop closes");
    }

    private static final class RecordingHandler implements EventHandler {
        private final HandlerPriority priority;
        private final AtomicReference<Thread> threadRef;

        private RecordingHandler(HandlerPriority priority, AtomicReference<Thread> threadRef) {
            this.priority = priority;
            this.threadRef = threadRef;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return priority;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            threadRef.compareAndSet(null, Thread.currentThread());
            return false;
        }
    }
}
