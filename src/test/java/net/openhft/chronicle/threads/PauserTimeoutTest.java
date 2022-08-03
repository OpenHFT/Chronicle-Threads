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

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.fail;

public class PauserTimeoutTest extends ThreadsTestCommon {
    Pauser[] pausersSupportTimeout = {
            Pauser.balanced(),
            Pauser.sleepy(),
            new BusyTimedPauser(),
            new TimeoutPauser(0),
            new LongPauser(0, 0, 1, 10, TimeUnit.MILLISECONDS),
//            new MilliPauser(1)
    };
    Pauser[] pausersDontSupportTimeout = {
            BusyPauser.INSTANCE,
            new YieldingPauser(0)};

    @Test
    public void pausersSupportTimeout() throws TimeoutException {
        int timeoutNS = 100_000_000;
        for (Pauser p : pausersSupportTimeout) {
            p.pause(timeoutNS, TimeUnit.NANOSECONDS);
            long start = System.nanoTime();
            while (System.nanoTime() < start + timeoutNS / 2)
                try {
                    p.pause(timeoutNS, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    fail(p + " timed out");
                }
            while (System.nanoTime() < start + timeoutNS * 2) ;
            try {
                // System.out.println(start + " timeoutNS " + (start + timeoutNS) + " now " + Time.uniqueId() + " past " + (System.nanoTime() > (start + timeoutNS)));
                p.pause(timeoutNS, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                continue;
            }
            fail(p + " did not timeoutNS");
        }
    }

    @Test
    public void pausersDontSupportTimeout() throws TimeoutException {
        for (Pauser p : pausersDontSupportTimeout) {
            try {
                p.pause(100, TimeUnit.MILLISECONDS);
            } catch (UnsupportedOperationException e) {
                continue;
            }
            fail(p + " did not throw");
        }
    }
}
