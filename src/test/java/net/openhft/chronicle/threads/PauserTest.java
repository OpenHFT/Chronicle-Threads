package net.openhft.chronicle.threads;

import org.junit.Test;

public class PauserTest extends ThreadsTestCommon {

    @Test
    public void sleepy() {
        Pauser.sleepy();
    }

    @Test
    public void yielding() {
        Pauser.yielding();
    }

    @Test
    public void busy() {
        Pauser.busy();
    }
}