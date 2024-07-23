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
import net.openhft.chronicle.core.io.InvalidMarshallableException;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.onoes.ExceptionHandler;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventLoopsTest extends ThreadsTestCommon {

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
        try (final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "test", Pauser.balanced(), false, "none");
             final BlockingEventLoop blockingEventLoop = new BlockingEventLoop("blocker")) {
            doTest(blockingEventLoop, mediumEventLoop);
        }
    }

    private static void doTest(BlockingEventLoop blockingEventLoop, MediumEventLoop mediumEventLoop) {
        blockingEventLoop.start();
        mediumEventLoop.start();

        Semaphore semaphore = new Semaphore(0);
        blockingEventLoop.addHandler(() -> {
            semaphore.acquireUninterruptibly();
            return false;
        });
        while (!semaphore.hasQueuedThreads()) {
            Jvm.pause(10);
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

    public static Stream<EventLoop> eventLoopsToClose() {
        return Stream.of(
                new MediumEventLoop(null, "medium", Pauser.balanced(), false, null),
                new BlockingEventLoop("blocking")
        );
    }

    @ParameterizedTest
    @MethodSource("eventLoopsToClose")
    public void closeFromEventLoopThreadThrowsException(EventLoop el) {
        try {
            AtomicBoolean exceptionThrownInHandler = new AtomicBoolean();
            AtomicBoolean eventHandlerFinished = new AtomicBoolean();

            EventHandler closingEventHandler = new EventHandler() {
                @Override
                public boolean action() throws InvalidEventHandlerException, InvalidMarshallableException {
                    try {
                        el.close();
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
            };

            el.addHandler(closingEventHandler);
            el.start();

            long timeoutTime = System.currentTimeMillis() + 500;
            while (!exceptionThrownInHandler.get()) {
                if (System.currentTimeMillis() > timeoutTime) {
                    Assertions.fail("Event loop " + el.name() + " didn't " + (eventHandlerFinished.get() ? "throw an exception when attempting to close" : "run in this time"));
                }
                Jvm.pause(10);
            }

            assertTrue(el.isAlive());
            assertFalse(el.isStopped());
            assertFalse(el.isClosed());
            assertFalse(el.isClosing());
        } finally {
            el.close();

            assertTrue(el.isClosed());
        }

    }
}
