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
import net.openhft.chronicle.core.threads.InterruptedRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlockingEventLoopTest extends ThreadsTestCommon {

    @Test
    public void handlersAreInterruptedOnStop() throws TimeoutException {
        try (final BlockingEventLoop el = new BlockingEventLoop("test-blocking-loop")) {
            el.start();

            AtomicBoolean wasStoppedSuccessfully = new AtomicBoolean(false);
            CyclicBarrier barrier = new CyclicBarrier(2);

            el.addHandler(() -> {
                waitQuietly(barrier);

                while (!Thread.currentThread().isInterrupted()) {
                    Jvm.pause(10);
                }
                wasStoppedSuccessfully.set(true);
                return false;
            });

            waitQuietly(barrier);
            Jvm.pause(10);
            el.stop();

            TimingPauser pauser = Pauser.balanced();
            while (!wasStoppedSuccessfully.get()) {
                pauser.pause(1, TimeUnit.SECONDS);
            }
            assertTrue(wasStoppedSuccessfully.get());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    private void waitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedRuntimeException("Interrupted waiting at barrier");
        }
    }
}
