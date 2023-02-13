/*
 * Copyright 2016-2022 chronicle.software
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
import net.openhft.chronicle.core.observable.Observable;
import net.openhft.chronicle.core.observable.StateReporter;
import net.openhft.chronicle.core.onoes.ExceptionHandler;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class EventLoopsTest {

    @Test
    public void stopAllCanHandleNulls() {
        final StringBuilder sb = new StringBuilder();
        final ExceptionHandler eh = (c, m, t) -> sb.append(m);
        ExceptionHandler exceptionHandler = Jvm.warn();
        try {
            Jvm.setWarnExceptionHandler(exceptionHandler);
            EventLoops.stopAll(null, Arrays.asList(null, null, null), null);
            // Should silently accept nulls
            assertTrue(sb.toString().isEmpty());
        } finally {
            Jvm.setWarnExceptionHandler(exceptionHandler);
        }
    }

    @Timeout(5_000)
    @Test
    public void stopAllWillBlockUntilTheLastEventLoopStops() {
        final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "test", Pauser.balanced(), false, "none");
        final BlockingEventLoop blockingEventLoop = new BlockingEventLoop("blocker");
        blockingEventLoop.start();
        mediumEventLoop.start();
        Semaphore semaphore = new Semaphore(0);
        blockingEventLoop.addHandler(() -> {
            semaphore.acquireUninterruptibly();
            return false;
        });
        while (!semaphore.hasQueuedThreads()) {
            Jvm.pause(100);
        }

        AtomicBoolean stoppedEm = new AtomicBoolean(false);
        new Thread(() -> {
            EventLoops.stopAll(mediumEventLoop, Arrays.asList(null, Collections.singleton(blockingEventLoop)));
            stoppedEm.set(true);
        }).start();
        long endTime = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < endTime) {
            assertFalse(stoppedEm.get());
        }
        semaphore.release();
        while (System.currentTimeMillis() < endTime) {
            if (stoppedEm.get()) {
                break;
            }
            Jvm.pause(1);
        }
    }

    @Test
    public void canDumpAllEventLoops() {
        try (EventGroup bigBoy = EventGroupBuilder.builder().build();
             MediumEventLoop standaloneMedium = new MediumEventLoop(null, "tester", Pauser.balanced(), true, "none")) {
            bigBoy.addHandler(new ObservableEventHandler("medium1", HandlerPriority.MEDIUM));
            bigBoy.addHandler(new ObservableEventHandler("medium2", HandlerPriority.MEDIUM));
            bigBoy.addHandler(new ObservableEventHandler("timer", HandlerPriority.TIMER));
            bigBoy.addHandler(new ObservableEventHandler("blocking", HandlerPriority.BLOCKING));
            final StringBuilder stringBuilder = new StringBuilder();
            EventLoops.dumpEventLoops(StateReporter.toAppendable(stringBuilder));
            assertEquals("eventLoops:\n" +
                    "   _id: EventGroup@<address>\n" +
                    "   name: \n" +
                    "   lifecycle: NEW\n" +
                    "   daemon: LongPauser\n" +
                    "   pauser: LongPauser\n" +
                    "   priorities: [HIGH, MEDIUM, TIMER, DAEMON, MONITOR, BLOCKING, REPLICATION, REPLICATION_TIMER, CONCURRENT]\n" +
                    "   concBinding: none\n" +
                    "   bindingReplication: none\n" +
                    "   coreEventLoop:\n" +
                    "      _id: VanillaEventLoop@<address>\n" +
                    "      name: core-event-loop\n" +
                    "      lifecycle: NEW\n" +
                    "      daemon: true\n" +
                    "      highHandler:\n" +
                    "         NOOP\n" +
                    "      mediumHandlers:\n" +
                    "         _id: ObservableEventHandler@<address>\n" +
                    "         name: medium1\n" +
                    "         priority: MEDIUM\n" +
                    "         iterations: 0\n" +
                    "         ---\n" +
                    "         _id: ObservableEventHandler@<address>\n" +
                    "         name: medium2\n" +
                    "         priority: MEDIUM\n" +
                    "         iterations: 0\n" +
                    "      newHandler:\n" +
                    "         <null>\n" +
                    "      thread:\n" +
                    "         <null>\n" +
                    "      executor.isShutdown: false\n" +
                    "      executor.isTerminated: false\n" +
                    "      timerIntervalMS: 1\n" +
                    "      timerEventHandlers:\n" +
                    "         _id: ObservableEventHandler@<address>\n" +
                    "         name: timer\n" +
                    "         priority: TIMER\n" +
                    "         iterations: 0\n" +
                    "      daemonHandlers:\n" +
                    "   monitorEventLoop:\n" +
                    "      _id: MonitorEventLoop@<address>\n" +
                    "      name: ~monitorevent~loop~monitor\n" +
                    "      lifecycle: NEW\n" +
                    "   blockingEventLoop:\n" +
                    "      _id: BlockingEventLoop@<address>\n" +
                    "      name: blocking-event-loop\n" +
                    "      lifecycle: NEW\n" +
                    "      pauser: LongPauser\n" +
                    "      handlers:\n" +
                    "         _id: ObservableEventHandler@<address>\n" +
                    "         name: blocking\n" +
                    "         priority: BLOCKING\n" +
                    "         iterations: 0\n" +
                    "      executorService.isShutdown(): false\n" +
                    "      executorService.isTerminated(): false\n" +
                    "      executorService.isTerminated(): false\n" +
                    "   replicationEventLoop:\n" +
                    "      <null>\n" +
                    "   ---\n" +
                    "   _id: VanillaEventLoop@<address>\n" +
                    "   ---\n" +
                    "   _id: MonitorEventLoop@<address>\n" +
                    "   ---\n" +
                    "   _id: BlockingEventLoop@<address>\n" +
                    "   ---\n" +
                    "   _id: MediumEventLoop@<address>\n" +
                    "   name: tester\n" +
                    "   lifecycle: NEW\n" +
                    "   daemon: true\n" +
                    "   highHandler:\n" +
                    "      NOOP\n" +
                    "   mediumHandlers:\n" +
                    "   newHandler:\n" +
                    "      <null>\n" +
                    "   thread:\n" +
                    "      <null>\n" +
                    "   executor.isShutdown: false\n" +
                    "   executor.isTerminated: false\n", stringBuilder.toString().replaceAll("@\\d+", "@<address>"));
        }
    }

    private static class ObservableEventHandler implements EventHandler, Observable {

        private final String name;
        private final HandlerPriority priority;
        private long iterations = 0;
        private EventLoop eventLoop;
        private boolean loopStartedCalled = false;
        private boolean loopFinishedCalled = false;

        private ObservableEventHandler(String name, HandlerPriority priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public void dumpState(StateReporter stateReporter) {
            stateReporter.writeProperty("name", name);
            stateReporter.writeProperty("priority", priority.name());
            stateReporter.writeProperty("iterations", String.valueOf(iterations));
        }

        @Override
        public boolean action() {
            iterations++;
            return false;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return priority;
        }

        @Override
        public void eventLoop(EventLoop eventLoop) {
            this.eventLoop = eventLoop;
        }

        @Override
        public void loopStarted() {
            this.loopStartedCalled = true;
        }

        @Override
        public void loopFinished() {
            this.loopFinishedCalled = true;
        }
    }
}