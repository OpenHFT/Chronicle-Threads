package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.OS;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

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
                    int delta = (OS.isWindows() || OS.isMacOSX()) ? 20 : 5;
                    // please don't add delta to pauseTimeMillis below - it makes this test flakier on Windows
                    assertEquals(pauseTimeMillis, time, delta);
                    tp.reset();
                    break;
                }
            }
        }
    }
}