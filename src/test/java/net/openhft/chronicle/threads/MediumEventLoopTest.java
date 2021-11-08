package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MediumEventLoopTest {

    @Test
    public void testAddingTwoEventHandlersBeforeStartingLoopIsThreadSafe() {
        for (int i = 0; i < 10_000; i++) {
            try (MediumEventLoop eventLoop = new MediumEventLoop(null, "name", Pauser.balanced(), true, null)) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                IntStream.range(0, 2).parallel()
                        .forEach(ignored -> {
                            try {
                                EventHandler handler = new NoOpHandler();
                                barrier.await();
                                eventLoop.addHandler(handler);
                            } catch (InterruptedException | BrokenBarrierException e) {
                                throw new RuntimeException(e);
                            }
                        });
                assertEquals(2, eventLoop.mediumHandlersArray.length);
            }
        }
    }

    private static class NoOpHandler implements EventHandler {

        @Override
        public boolean action() {
            return false;
        }
    }
}