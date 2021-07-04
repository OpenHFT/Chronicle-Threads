/*
 * Copyright 2015 Higher Frequency Trading
 *
 * http://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class LongPauserTest extends ThreadsTestCommon {
    @Test
    public void testLongPauser() {
        final LongPauser pauser = new LongPauser(1, 1, 100, 1000, TimeUnit.MICROSECONDS);
        Thread thread = new Thread() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    pauser.pause();
                    Thread.yield();
                }
            }
        };
        thread.start();

        for (int t = 0; t < 3; t++) {
            long start = System.nanoTime();
            int runs = 10000000;
            for (int i = 0; i < runs; i++)
                pauser.unpause();
            long time = System.nanoTime() - start;
            // System.out.printf("Average time to unpark was %,d ns%n", time / runs);
            Jvm.pause(20);
        }
        thread.interrupt();
    }

    @Test
    public void unpauseStopsPausing() throws InterruptedException {
        final int pauseMillis = 1_000;
        final LongPauser pauser = new LongPauser(0, 0, pauseMillis, pauseMillis, TimeUnit.MILLISECONDS);
        final CountDownLatch started = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            started.countDown();
            pauser.pause();
        });
        thread.start();
        started.await(50, TimeUnit.MILLISECONDS);
        
        pauser.unpause();
        final long startNs = System.nanoTime();
        thread.join();
        final long timeTakenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        Assert.assertTrue("Took " + timeTakenMs + " to stop", timeTakenMs < pauseMillis / 10);
    }

    @Test
    public void testLongAsyncPauser() {
        final LongPauser pauser = new LongPauser(0, 0, 1, 1, TimeUnit.MILLISECONDS);
        for (int i = 0; i < 5; i++) {
            pauser.asyncPause();
            testUntilUnpaused(pauser, 1, TimeUnit.MILLISECONDS);
            pauser.reset();
            testUntilUnpaused(pauser, 0, TimeUnit.MILLISECONDS);
        }
    }

    static void testUntilUnpaused(LongPauser pauser, int n, TimeUnit timeUnit) {
        long timeNS = timeUnit.convert(n, TimeUnit.NANOSECONDS);
        long start = System.nanoTime();
        while (pauser.asyncPausing()) {
            if (System.nanoTime() > start + timeNS + 100_000_000)
                fail();
        }
        long time = System.nanoTime() - start;
        final int delta = 11_000_000;
        assertEquals(timeNS + delta, time, delta);
    }
}
