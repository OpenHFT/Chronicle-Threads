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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PauserTest extends ThreadsTestCommon {

    @Test
    public void balanced() throws TimeoutException {
        doTest(Pauser.balanced());
    }

    @Test
    public void balancedUpToMillis1() throws TimeoutException {
        doTest(Pauser.balancedUpToMillis(1));
    }

    @Test
    public void busy() throws TimeoutException {
        Pauser pauser = BusyPauser.INSTANCE;
        assertEquals(0, pauser.countPaused());
        assertEquals(0, pauser.timePaused());
        pauser.pause();
        try {
            pauser.pause(1, TimeUnit.MILLISECONDS);
        } catch (UnsupportedOperationException ignored) {
        }
        assertEquals(0, pauser.countPaused());
        pauser.unpause();
        assertTrue(pauser.isBusy());
    }

    @Test
    public void millis1() throws TimeoutException {
        doTest(Pauser.millis(1));
    }

    @Test
    public void sleepy() throws TimeoutException {
        doTest(Pauser.sleepy());
    }

    @Test
    public void timedBusy() throws TimeoutException {
        doTest(Pauser.timedBusy());
    }

    @Test
    public void yielding() throws TimeoutException {
        doTest(Pauser.yielding());
    }

    private void doTest(Pauser pauser) {
        assertEquals(0, pauser.countPaused());
        assertEquals(0, pauser.timePaused());
        pauser.pause();
        assertEquals(1, pauser.countPaused());
        pauser.pause();
        assertEquals(2, pauser.countPaused());
        pauser.unpause();
        assertEquals(pauser.getClass().getSimpleName().contains("Busy"),
                pauser.isBusy());
    }
}