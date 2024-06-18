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

import net.openhft.chronicle.core.io.InvalidMarshallableException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.testframework.ExecutorServiceUtil;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class MediumEventLoopTest extends ThreadsTestCommon {

    @Test
    public void testAddingTwoEventHandlersBeforeStartingLoopIsThreadSafe() {
        for (int i = 0; i < 10_000; i++) {
            try (MediumEventLoop eventLoop = new MediumEventLoop(null, "name", Pauser.balanced(), true, null)) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                IntStream.range(0, 2).parallel()
                        .forEach(ignored -> {
                            try {
                                EventHandler handler = new NoOpHandler();
                                barrier.await();
                                eventLoop.addHandler(handler);
                            } catch (InterruptedException | BrokenBarrierException e) {
                                throw new RuntimeException(e);
                            }
                        });
                assertEquals(2, eventLoop.mediumHandlersArray.length);
            }
        }
    }

    @Test
    public void testAddingTwoEventHandlersWithBlockedMainLoopDoesNotHang() {
        for (int i = 0; i < 10_000; i++) {
            try (MediumEventLoop eventLoop = new MediumEventLoop(null, "name", Pauser.balanced(), true, null)) {
                eventLoop.start();
                CyclicBarrier barrier = new CyclicBarrier(3);
                eventLoop.addHandler(new EventHandler() {
                    @Override
                    public boolean action() throws InvalidEventHandlerException, InvalidMarshallableException {
                        try {
                            barrier.await();
                            return false;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InvalidEventHandlerException();
                        } catch (BrokenBarrierException e) {
                            throw new InvalidEventHandlerException();
                        }
                    }
                });
                IntStream.range(0, 2).parallel()
                        .forEach(ignored -> {
                            try {
                                EventHandler handler = new NoOpHandler();
                                eventLoop.addHandler(handler);
                                barrier.await();
                            } catch (InterruptedException | BrokenBarrierException e) {
                                throw new RuntimeException(e);
                            }
                        });

                Waiters.waitForCondition("Not all handlers arrived in the loop",
                        () -> eventLoop.mediumHandlersArray.length == 3, 1000);
            }
        }
    }

    @Test
    void illegalStateExceptionsAreLoggedWhenThrownInLoopStarted() throws InterruptedException {
        try (MediumEventLoop eventLoop = new MediumEventLoop(null, "name", Pauser.balanced(), true, null)) {
            class ThrowingHandler implements EventHandler {

                @Override
                public void loopStarted() {
                    throw new IllegalStateException("Something went wrong in loopStarted!!!");
                }

                @Override
                public boolean action() {
                    return false;
                }

                @Override
                public void loopFinished() {
                    throw new IllegalStateException("Something went wrong in loopFinished!!!");
                }
            }

            try {
                // Add handler before loop has started. loopStarted not called yet.
                eventLoop.addHandler(new ThrowingHandler());

                // Start the loop. loopStarted called.
                eventLoop.start();

                while (!eventLoop.isStarted()) {
                    Thread.sleep(100);
                }

                expectException("Something went wrong in loopStarted!!!");
                expectException("Something went wrong in loopFinished!!!");
            } catch (Throwable t) {
                fail("Unexpected exception", t);
            }
        }
    }

    @Test
    void throwableExceptionsAreLoggedWhenThrownInLoopStarted() throws InterruptedException {
        try (MediumEventLoop eventLoop = new MediumEventLoop(null, "name", Pauser.balanced(), true, null)) {
            class ThrowingHandler implements EventHandler {

                @Override
                public void loopStarted() {
                    throw new IllegalStateException("Something went wrong in loopStarted!!!");
                }

                @Override
                public boolean action() {
                    return false;
                }

                @Override
                public void loopFinished() {
                    System.out.println("finished");
                }
            }
            eventLoop.start();
            while(!eventLoop.isStarted()) {
                Thread.sleep(100);
            }

            // Add handler after loop has started
            expectException("Something went wrong in loopStarted!!!");
            eventLoop.addHandler(new ThrowingHandler());

            // Not ideal, but we need to wait for the exception to be thrown
            Thread.sleep(1000);

            if (eventLoop.isStopped()) {
                fail("Event loop stopped unexpectedly");
            }
        }
    }

    @Test
    void concurrentStartStopDoesNoThrowError() throws ExecutionException, InterruptedException {
        ExecutorService es = Executors.newCachedThreadPool();
        for (int i = 0; i < 100; i++) {
            try (final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "test", Pauser.balanced(), false, "any")) {
                final Future<?> starter = es.submit(mediumEventLoop::start);
                final Future<?> stopper = es.submit(mediumEventLoop::stop);
                starter.get();
                stopper.get();
            }
        }
        ExecutorServiceUtil.shutdownAndWaitForTermination(es);
    }

    private static class NoOpHandler implements EventHandler {

        @Override
        public boolean action() {
            return false;
        }
    }
}
