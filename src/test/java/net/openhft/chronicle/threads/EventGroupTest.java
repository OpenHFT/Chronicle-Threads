package net.openhft.chronicle.threads;

import net.openhft.chronicle.threads.api.InvalidEventHandlerException;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Rob Austin.
 */
public class EventGroupTest {

    @Test
    public void testSimeEventGroupTest() throws Exception {

        final AtomicInteger value = new AtomicInteger();

        final EventGroup eventGroup = new EventGroup(true);
        try {
            eventGroup.start();

            eventGroup.addHandler(() -> {
                if (value.get() == 10)
                    // throw this if you don't wish to be called back
                    throw new InvalidEventHandlerException();
                value.incrementAndGet();
                return true;
            });

            final long start = System.currentTimeMillis();

            Thread.sleep(1);
            while (value.get() != 10) {
            }

            Assert.assertTrue(System.currentTimeMillis() < start + TimeUnit.SECONDS.toMillis(5));

            for (int i = 0; i < 10; i++) {
                Assert.assertEquals(10, value.get());
                Thread.sleep(1);
            }

        } catch (Exception e) {
            eventGroup.stop();
        }
    }


}
