package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class TimeoutPauserTest {

    @Test
    public void pause() {
        final int pauseTimeMillis = 100;
        final TimeoutPauser tp = new TimeoutPauser(pauseTimeMillis);
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
                    int delta = 20;
                    // please don't add delta to pauseTimeMillis below - it makes this test flakier on Windows
                    assertEquals(pauseTimeMillis, time, delta);
                    tp.reset();
                    break;
                }
            }
        }
    }
}