package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.threads.*;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;


public class EventLoopStatisticsCollector {

    private static volatile EventLoopStatisticsCollector instance;
    private EventLoopStatisticsCollector() {}

    private long periodMS;
    private HashSet<Long> eventLoopTids;
    private VanillaEventLoop statisticsEventLoop;
    private Timer timer;
    private ChronicleQueue queue;


    private EventLoopStatisticsCollector(long periodMS, RollCycle rollCycle) {
        this.periodMS = periodMS;
        this.eventLoopTids = new HashSet<>();

        this.statisticsEventLoop = new VanillaEventLoop(null, "statistics-event-loop", PauserMode.busy.get(), 20, true, "any", EnumSet.of(HandlerPriority.TIMER));
        this.statisticsEventLoop.start();
        this.timer = new Timer(statisticsEventLoop);

        this.queue = SingleChronicleQueueBuilder.single(OS.getTarget() + "/eventloop-statistics").rollCycle(rollCycle).build();
    }

    public static EventLoopStatisticsCollector getStatisticsCollector() {
        if (instance == null) {
            synchronized (EventLoopStatisticsCollector.class) {
                if (instance == null) {
                    instance = new EventLoopStatisticsCollector(1_000, RollCycles.HOURLY);
                }
            }
        }
        return instance;
    }


    public void collectEventLoopStatistics(MediumEventLoop eventLoop) throws NullPointerException{

        long tid = Objects.requireNonNull(eventLoop.thread()).getId();
        if (!eventLoopTids.contains(tid)) {
            final ExcerptAppender appender = queue.acquireAppender();
            eventLoopTids.add(tid);

            AtomicLong currentTimePaused = new AtomicLong();
            AtomicLong lastTimePaused = new AtomicLong();
            AtomicLong currentTimeMS = new AtomicLong();
            AtomicLong lastTimeMS = new AtomicLong();

            currentTimePaused.set(eventLoop.pauser.timePaused());
            currentTimeMS.set(System.currentTimeMillis());

            timer.scheduleAtFixedRate((EventHandler) () -> {
                if (eventLoop.isAlive()) {
                    lastTimePaused.set(currentTimePaused.getAndSet(eventLoop.pauser.timePaused()));
                    lastTimeMS.set(currentTimeMS.getAndSet(System.currentTimeMillis()));

                    appender.writeDocument(w -> w.write("statistic").marshallable(
                            m -> m.write("tid").int16(tid)
                                    .write("type").text(eventLoop.getClass().getSimpleName())
                                    .write("pauser").text(eventLoop.pauser.getClass().getSimpleName())
                                    .write("active %").float32(100 * (1 - ((float) currentTimePaused.get() - lastTimePaused.get()) / (currentTimeMS.get() - lastTimeMS.get())))));
                    return true;
                } else {
                    eventLoopTids.remove(tid);
                    throw InvalidEventHandlerException.reusable();
                }
            }, instance.periodMS, instance.periodMS);
        }
    }

    public void dumpStatistics() {
        ChronicleQueue queue = getStatisticsCollector().queue;
        System.out.println("Queue dump: \n" + queue.dump());
        queue.close();
    }

}