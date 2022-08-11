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

import net.openhft.chronicle.core.threads.EventHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MediumEventLoopTest extends ThreadsTest {

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
    void illegalStateExceptionsAreLoggedWhenThrownInLoopStarted() {
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
            }
            eventLoop.addHandler(new ThrowingHandler());
            eventLoop.start();
            expectException("Something went wrong in loopStarted!!!");
        }
    }

    private static class NoOpHandler implements EventHandler {

        @Override
        public boolean action() {
            return false;
        }
    }
}