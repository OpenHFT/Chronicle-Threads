/*
 * Copyright 2015 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
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
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/*
 * Created by peter.lawrey on 11/12/14.
 */
public class LongPauserTest {
    @Test
    public void testLongPauser() throws InterruptedException {
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
            System.out.printf("Average time to unpark was %,d ns%n", time / runs);
            Jvm.pause(20);
        }
        thread.interrupt();
    }
}
