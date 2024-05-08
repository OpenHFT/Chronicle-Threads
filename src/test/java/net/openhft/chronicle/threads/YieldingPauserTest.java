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

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class YieldingPauserTest extends ThreadsTestCommon {

    @Test
    public void pause() {
        final int pauseTimeMillis = 100;
        final YieldingPauser tp = new YieldingPauser(pauseTimeMillis);
        for (int i = 0; i < 10; i++) {
            final long start = System.currentTimeMillis();
            while (true) {
                try {
                    tp.pause(pauseTimeMillis, TimeUnit.MILLISECONDS);
                    if (System.currentTimeMillis() - start > 200)
                        fail();
                } catch (TimeoutException e) {
                    final long time = System.currentTimeMillis() - start;
                    // delta used to be 5 for Linux but occasionally we see it blow in Continuous Integration
                    // a delta of 20 was used here, however in some situations in CI that was not sufficient:
                    // org.opentest4j.AssertionFailedError: expected: <100.0> but was: <126.0>
                    int delta = 30;
                    // please don't add delta to pauseTimeMillis below - it makes this test flakier on Windows
                    assertEquals(pauseTimeMillis, time, delta);
                    tp.reset();
                    break;
                }
            }
        }
    }
}