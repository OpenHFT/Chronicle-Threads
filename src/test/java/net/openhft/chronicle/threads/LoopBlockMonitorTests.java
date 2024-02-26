package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.InvalidMarshallableException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.testframework.Waiters;
import net.openhft.chronicle.threads.internal.ThreadMonitorHarnessListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open questions:
 *
 * <ul>
 *     <li>Why does medium event loop behave differently to vanilla event loop?</li>
 * </ul>
 *
 * TODO: Parameterize tests for different event loop types, medium vs vanilla
 */
class LoopBlockMonitorTests {

    private static final Logger log = LoggerFactory.getLogger(LoopBlockMonitorTests.class);

    private RecordingThreadMonitorHarnessListener listener;

    @BeforeEach
    public void beforeEach() {
        clearConfig();
        setMonitorInitialDelayMs(5);
        listener = new RecordingThreadMonitorHarnessListener();
    }

    @AfterEach
    void afterEach() {
        clearConfig();
    }

    @Test
    void mediumEventLoop_reportsBlockedAtLeastOnce() {
        enableLoopBlockMonitoring();
        setTimeout(5);

        EventGroup eventGroup = EventGroup.builder()
                .withPauser(Pauser.busy())
                .withPriorities(HandlerPriority.MEDIUM)
                .withName("test")
                .build();

        eventGroup.setThreadMonitorHarnessListener(listener);

        try {
            CountingEventHandler countingEventHandler = new CountingEventHandler();
            eventGroup.addHandler(countingEventHandler);
            eventGroup.addHandler(new BlockingEventHandler(20));
            eventGroup.start();

            Waiters.waitForCondition("Waiting for at least one block to be reported", () -> listener.blockedCounter.get() > 0, 2_000);
            assertTrue(listener.blockedCounter.get() > 0);
        } finally {
            eventGroup.stop();
            eventGroup.close();
        }
    }

    @Test
    void mediumEventLoop_reportsBlockedNTimesCorrectly() {
        enableLoopBlockMonitoring();
        setTimeout(5);

        EventGroup eventGroup = EventGroup.builder()
                .withPauser(Pauser.busy())
                .withPriorities(HandlerPriority.MEDIUM)
                .withName("test")
                .build();

        eventGroup.setThreadMonitorHarnessListener(listener);

        try {
            CountingEventHandler countingEventHandler = new CountingEventHandler();
            eventGroup.addHandler(countingEventHandler);
            eventGroup.addHandler(new BlockingEventHandler(10));
            eventGroup.start();

            Waiters.waitForCondition("Waiting for at least one block to be reported", () -> listener.blockedCounter.get() > 2, 3_000);
            System.out.println("Count->" + listener.blockedCounter.get());
            assertTrue(listener.blockedCounter.get() > 2);
        } finally {
            eventGroup.stop();
            eventGroup.close();
        }
    }

    private void clearConfig() {
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = MonitorEventLoop.MONITOR_INITIAL_DELAY_MS_DEFAULT_VALUE;
        System.clearProperty("disableLoopBlockMonitor");
        System.clearProperty("MONITOR_INTERVAL_MS");
    }

    private void enableLoopBlockMonitoring() {
        System.setProperty("disableLoopBlockMonitor", "false");
    }

    private void disableLoopBlockMonitoring() {
        System.setProperty("disableLoopBlockMonitor", "true");
    }

    /**
     * Event loop monitoring will not kick in at all until this delay has elapsed. By default, this number is set to 10s
     * making testing a slow process - set it to much lower values to prevent test slow down.
     */
    private void setMonitorInitialDelayMs(int initialDelayMs) {
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = initialDelayMs;
    }

    private void setTimeout(long millis) {
        System.setProperty("MONITOR_INTERVAL_MS", Long.toString(millis));
    }

    private static class RecordingThreadMonitorHarnessListener implements ThreadMonitorHarnessListener {

        private final AtomicLong blockedCounter = new AtomicLong(0);

        @Override
        public void blocked() {
            blockedCounter.incrementAndGet();
        }

    }

    private static class CountingEventHandler implements EventHandler {

        private final AtomicLong counter = new AtomicLong(0);

        @Override
        public boolean action() throws InvalidMarshallableException {
            counter.incrementAndGet();
            return false;
        }

        public long count() {
            return counter.get();
        }

    }

    private static class BlockingEventHandler implements EventHandler {

        private final long blockMillis;

        private BlockingEventHandler(long blockMillis) {
            this.blockMillis = blockMillis;
        }

        @Override
        public boolean action() throws InvalidMarshallableException {
            Jvm.pause(blockMillis);
            return false;
        }
    }

}
