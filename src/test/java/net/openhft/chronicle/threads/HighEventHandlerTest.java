package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;

public class HighEventHandlerTest {
    int high = 0;
    int[] medium = new int[4];

    @Test
    public void highPriorityMEL() {
        MediumEventLoop el = new MediumEventLoop(null, "test", Pauser.balanced(), true, "none");
        doTest(el);
    }

    @Test
    public void highPriorityVEL() {
        VanillaEventLoop el = new VanillaEventLoop(null, "test", Pauser.balanced(), 1, true, "none", VanillaEventLoop.ALLOWED_PRIORITIES);
        doTest(el);
    }

    public void doTest(EventLoop el) {
        el.start();
        el.addHandler(new MyHigh());
        el.addHandler(new MyMedium(3));
        el.addHandler(new MyMedium(2));
        el.addHandler(new MyMedium(1));
        el.addHandler(new MyMedium(0));
        while (medium[0] < 10000)
            Thread.yield();
        el.stop();
        Arrays.sort(medium);
        assertEquals(medium[0], medium[medium.length - 1], 1);
        assertEquals(IntStream.of(medium).sum(), high, 1);

        System.out.println(high + " " +
                IntStream.of(medium)
                        .mapToObj(Integer::toString)
                        .collect(Collectors.joining(",")));
    }

    class MyMedium implements EventHandler {
        final int i;
        boolean reset;

        MyMedium(int i) {
            this.i = i;
        }

        @Override
        public boolean action() {
            if (!reset) {
                reset = true;
                high = 0;
                IntStream.range(0, medium.length)
                        .forEach(i -> medium[i] = 0);
            }
            medium[i]++;
            return true;
        }
    }

    class MyHigh implements EventHandler {
        @Override
        public boolean action() {
            high++;
            return true;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return HandlerPriority.HIGH;
        }
    }
}
