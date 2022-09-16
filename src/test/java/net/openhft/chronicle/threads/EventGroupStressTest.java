package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.testframework.process.JavaProcessBuilder;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class EventGroupStressTest {

    private static final int NUM_PROCESSES = 10;
    private static final int NUM_GROUPS_PER_PROCESS = 20;

    @Test
    @Timeout(30)
    void canOverloadTheCPUWithEventGroupsSafely() {
        assumeFalse(OS.isWindows());
        IntStream.range(0, NUM_PROCESSES).mapToObj(i -> JavaProcessBuilder.create(EventGroupStarterProcess.class)
                        .withProgramArguments(String.valueOf(NUM_GROUPS_PER_PROCESS))
                        .start())
                .forEach(process -> {
                    try {
                        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                            Jvm.error().on(EventGroupStressTest.class, "Process didn't end or ended in error");
                            JavaProcessBuilder.printProcessOutput("event group getter", process);
                        }
                    } catch (InterruptedException e) {
                        Jvm.error().on(EventGroupStressTest.class, "Interrupted waiting for process to end");
                        Thread.currentThread().interrupt();
                    }
                });
    }

    static class EventGroupStarterProcess {

        public static void main(String[] args) {
            int groupsToStart = Integer.parseInt(args[0]);
            List<EventGroup> eventGroups = new ArrayList<>();
            List<TestEventHandler> handlers = new ArrayList<>();
            try {
                for (int j = 0; j < groupsToStart; j++) {
                    final EventGroup eventGroup = EventGroup.builder().withBinding("any").build();
                    final TestEventHandler beforeStartHandler = new TestEventHandler();
                    eventGroup.addHandler(beforeStartHandler);
                    eventGroup.start();
                    final TestEventHandler afterStartHandler = new TestEventHandler();
                    eventGroup.addHandler(afterStartHandler);
                    handlers.add(beforeStartHandler);
                    handlers.add(afterStartHandler);
                    eventGroups.add(eventGroup);
                }
                while (!handlers.stream().allMatch(handler -> handler.loopStarted)) {
                    Jvm.pause(100);
                }
            } finally {
                eventGroups.forEach(Closeable::closeQuietly);
            }
        }
    }

    static class TestEventHandler implements EventHandler {

        private static final HandlerPriority[] PRIORITIES = new HandlerPriority[]{
                HandlerPriority.HIGH, HandlerPriority.MEDIUM, HandlerPriority.REPLICATION, HandlerPriority.TIMER,
                HandlerPriority.BLOCKING, HandlerPriority.DAEMON
        };

        private final HandlerPriority priority;
        private volatile boolean loopStarted = false;

        public TestEventHandler() {
            this.priority = PRIORITIES[ThreadLocalRandom.current().nextInt(PRIORITIES.length)];
        }

        @Override
        public void loopStarted() {
            loopStarted = true;
        }

        @Override
        public boolean action() {
            // Does nothing
            return false;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return priority;
        }
    }
}
