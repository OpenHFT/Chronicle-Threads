package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.FlakyTestRunner;
import net.openhft.chronicle.core.OS;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TimeoutPauserTest {

    @Test
    public void pause() {
        FlakyTestRunner.run(this::pause0);
    }

    private void pause0() {
        TimeoutPauser tp = new TimeoutPauser(100);
        for (int i = 0; i < 10; i++) {
            long start = System.currentTimeMillis();
            while (true) {
                try {
                    tp.pause(100, TimeUnit.MILLISECONDS);
                    if (System.currentTimeMillis() - start > 110)
                        fail();
                } catch (TimeoutException e) {
                    int delta = OS.isWindows() ? 20 : 5;
                    assertEquals(105, System.currentTimeMillis() - start, delta);
                    tp.reset();
                    break;
                }
            }
        }
    }
}