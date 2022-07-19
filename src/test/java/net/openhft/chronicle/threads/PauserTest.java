package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        Pauser pauser = Pauser.busy();
        assertEquals(0, pauser.countPaused());
        assertEquals(0, pauser.timePaused());
        pauser.pause();
        try {
            pauser.pause(1, TimeUnit.MILLISECONDS);
        } catch (UnsupportedOperationException ignored) {
        }
        assertEquals(0, pauser.countPaused());
        pauser.unpause();
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

    private void doTest(Pauser pauser) throws TimeoutException {
        assertEquals(0, pauser.countPaused());
        assertEquals(0, pauser.timePaused());
        pauser.pause();
        assertEquals(1, pauser.countPaused());
        pauser.pause(1, TimeUnit.MILLISECONDS);
        assertEquals(2, pauser.countPaused());
        pauser.unpause();
    }
}