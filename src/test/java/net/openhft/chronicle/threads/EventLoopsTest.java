package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class EventLoopsTest {

    @Test
    public void stopAllCanHandleNulls() {
        EventLoops.stopAll(null, Arrays.asList(null, null, null), null);
    }

    @Timeout(5_000)
    @Test
    public void stopAllWillBlockUntilTheLastEventLoopStops() {
        final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "test", Pauser.balanced(), false, "none");
        final BlockingEventLoop blockingEventLoop = new BlockingEventLoop("blocker");
        blockingEventLoop.start();
        mediumEventLoop.start();
        Semaphore semaphore = new Semaphore(0);
        blockingEventLoop.addHandler(() -> {
            semaphore.acquireUninterruptibly();
            return false;
        });
        while (!semaphore.hasQueuedThreads()) {
            Jvm.pause(100);
        }

        AtomicBoolean stoppedEm = new AtomicBoolean(false);
        new Thread(() -> {
            EventLoops.stopAll(mediumEventLoop, Arrays.asList(null, Collections.singleton(blockingEventLoop)));
            stoppedEm.set(true);
        }).start();
        long endTime = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < endTime) {
            assertFalse(stoppedEm.get());
        }
        semaphore.release();
        while (System.currentTimeMillis() < endTime) {
            if (stoppedEm.get()) {
                break;
            }
            Jvm.pause(1);
        }
    }
}