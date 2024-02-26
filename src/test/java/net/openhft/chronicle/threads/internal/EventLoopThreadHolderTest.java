package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.threads.CoreEventLoop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventLoopThreadHolderTest {

    private CoreEventLoop coreEventLoop;
    private EventLoopThreadHolder eventLoopThreadHolder;

    @BeforeEach
    void beforeEach() {
        coreEventLoop = mock(CoreEventLoop.class);
        eventLoopThreadHolder = new EventLoopThreadHolder(10, coreEventLoop);
    }

    @Test
    void startTime() {
        assertEquals(0, eventLoopThreadHolder.startedNS());
    }

    @Test
    void startTimeReturnsCoreEventLoopLoopStartNs() {
        when(coreEventLoop.loopStartNS()).thenReturn(10L);
        assertEquals(10L, eventLoopThreadHolder.startedNS());
    }

    @Test
    void shouldLog() {
        assertFalse(eventLoopThreadHolder.shouldLog(0));
    }

    @Test
    void shouldLog_timeElapsed() {
        assertTrue(eventLoopThreadHolder.shouldLog(100));
    }

    // Is this correct?
    @Test
    void shouldLog_multipleCallsTimeHasNotAdvanced() {
        assertTrue(eventLoopThreadHolder.shouldLog(100));
        assertTrue(eventLoopThreadHolder.shouldLog(101));
        assertTrue(eventLoopThreadHolder.shouldLog(102));
    }

    @Test
    void printBlockTime_baseCase() {
        assertEquals(10, eventLoopThreadHolder.getPrintBlockTimeNS());
    }

    @Test
    void printBlockTime_scaleUp_oneCall() {
        assertEquals(10, eventLoopThreadHolder.getPrintBlockTimeNS());
        eventLoopThreadHolder.dumpThread(millisAsNanos(10), millisAsNanos(11));
        assertEquals(20, eventLoopThreadHolder.getPrintBlockTimeNS());
    }

    @Test
    void printBlockTime_scaleUp_twoCalls() {
        assertEquals(10, eventLoopThreadHolder.getPrintBlockTimeNS());
        eventLoopThreadHolder.dumpThread(millisAsNanos(10), millisAsNanos(11));
        eventLoopThreadHolder.dumpThread(millisAsNanos(12), millisAsNanos(13));
        assertEquals(34, eventLoopThreadHolder.getPrintBlockTimeNS());
    }

    @Test
    void printBlockTime_scaleUp_threeCalls() {
        assertEquals(10, eventLoopThreadHolder.getPrintBlockTimeNS());
        eventLoopThreadHolder.dumpThread(millisAsNanos(10), millisAsNanos(11));
        eventLoopThreadHolder.dumpThread(millisAsNanos(12), millisAsNanos(13));
        eventLoopThreadHolder.dumpThread(millisAsNanos(13), millisAsNanos(14));
        assertEquals(53, eventLoopThreadHolder.getPrintBlockTimeNS());
    }

    @Test
    void printBlockTime_growsUnbounded() {
        for (int i = 10; i < 100; i++) {
            eventLoopThreadHolder.dumpThread(millisAsNanos(i), millisAsNanos(i + 1));
            eventLoopThreadHolder.getPrintBlockTimeNS();
        }
        assertEquals(16665, eventLoopThreadHolder.getPrintBlockTimeNS());
    }

    @Test
    void printBlockTime_reset() {
        for (int i = 10; i < 100; i++) {
            eventLoopThreadHolder.dumpThread(millisAsNanos(i), millisAsNanos(i + 1));
            eventLoopThreadHolder.resetTimers();
        }
        assertEquals(10, eventLoopThreadHolder.getPrintBlockTimeNS());
    }

    private static long millisAsNanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private void setStartTimeNs(long startTimeNs) {
        when(coreEventLoop.loopStartNS()).thenReturn(startTimeNs);
    }

}