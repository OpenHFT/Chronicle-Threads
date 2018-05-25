/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;

/**
 * @author Rob Austin.
 */
public class EventGroupTest {

    @Test
    public void testSimpleEventGroupTest() throws InterruptedException {

        final AtomicInteger value = new AtomicInteger();

        Thread t;
        try (final EventLoop eventGroup = new EventGroup(true)) {
            eventGroup.start();
            t = new Thread(eventGroup::awaitTermination);
            t.start();
            eventGroup.addHandler(() -> {
                if (value.get() == 10)
                    // throw this if you don't wish to be called back
                    throw new InvalidEventHandlerException();
                value.incrementAndGet();
                return true;
            });

            final long start = System.currentTimeMillis();
            while (value.get() != 10) {
                Jvm.pause(10);
            }

            Assert.assertTrue(System.currentTimeMillis() < start + TimeUnit.SECONDS.toMillis(5));

            for (int i = 0; i < 10; i++) {
                Assert.assertEquals(10, value.get());
                Jvm.pause(1);
            }
        }
        t.join(100);
        assertFalse(t.isAlive());
    }

    @Test(timeout = 1000)
    public void testCloseAwaitTerminationWithoutStarting() throws InterruptedException {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.close();
        eventGroup.awaitTermination();
    }
}
