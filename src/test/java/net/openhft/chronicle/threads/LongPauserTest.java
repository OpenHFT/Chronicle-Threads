/*
 * Copyright 2015 Higher Frequency Trading
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
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class LongPauserTest extends ThreadsTestCommon {

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
        Jvm.pause(10);  // give the thread some time to park
        pauser.unpause();
        final long startNs = System.nanoTime();
        thread.join();
        final long timeTakenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        assertTrue(timeTakenMs < pauseMillis / 5, "Took " + timeTakenMs + " to stop");
    }

    @Test
    public void testLongAsyncPauser() {
        final LongPauser pauser = new LongPauser(0, 0, 1, 1, TimeUnit.MILLISECONDS);
        boolean failedOnce = false;
        for (int i = 0; i < 100; i++) {
            try {
                pauser.asyncPause();
                testUntilUnpaused(pauser, 1, TimeUnit.MILLISECONDS);
                pauser.reset();
                testUntilUnpaused(pauser, 0, TimeUnit.MILLISECONDS);
            } catch (AssertionError e) {
                if (failedOnce)
                    throw e;
                failedOnce = true;
            }
        }
    }

    @Test
    void asyncPauseIsResetOnReset() {
        final LongPauser longPauser = new LongPauser(0, 0, 1, 1, TimeUnit.SECONDS);
        longPauser.asyncPause();
        assertTrue(longPauser.asyncPausing());
        longPauser.reset();
        assertFalse(longPauser.asyncPausing());
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
