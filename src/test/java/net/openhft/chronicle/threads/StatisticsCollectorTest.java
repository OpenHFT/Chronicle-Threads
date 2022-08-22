package net.openhft.chronicle.threads;

import org.junit.jupiter.api.Test;
import static net.openhft.chronicle.threads.EventLoopStatisticsCollector.getStatisticsCollector;


public class StatisticsCollectorTest {

    @Test
    void testEventLoopStatisticsCollection() throws InterruptedException {

        try (final MediumEventLoop fooMediumEventLoop = new MediumEventLoop(null, "foo-event-loop", Pauser.sleepy(), true, "any")) {
            fooMediumEventLoop.start();
            fooMediumEventLoop.addHandler(() -> (int) (Math.random() * 10) > 5);

            try (final MediumEventLoop barMediumEventLoop = new MediumEventLoop(null, "bar-event-loop", Pauser.sleepy(), true, "any")){
                barMediumEventLoop.start();
                barMediumEventLoop.addHandler(() -> (int) (Math.random() * 10) > 2);

                getStatisticsCollector().collectEventLoopStatistics(fooMediumEventLoop);
                getStatisticsCollector().collectEventLoopStatistics(barMediumEventLoop);

                fooMediumEventLoop.thread().sleep(3_000);
                fooMediumEventLoop.stop();
                barMediumEventLoop.thread().sleep(2_000);
                getStatisticsCollector().dumpStatistics();
            }
        }
    }

}


