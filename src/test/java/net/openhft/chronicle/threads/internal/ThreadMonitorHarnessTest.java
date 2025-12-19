/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.ThreadHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static net.openhft.chronicle.threads.CoreEventLoop.NOT_IN_A_LOOP;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThreadMonitorHarnessTest {

    private static final long TIMING_TOLERANCE_NS = 10_000_000;

    private ThreadMonitorHarness threadMonitorHarness;

    @Mock
    private ThreadHolder threadHolder;
    @Mock
    private LongSupplier timeSupplier;

    @BeforeEach
    void setUp() {
        threadMonitorHarness = new ThreadMonitorHarness(threadHolder, timeSupplier);
        lenient().when(threadHolder.isAlive()).thenReturn(true);
        lenient().when(threadHolder.timingToleranceNS()).thenReturn(TIMING_TOLERANCE_NS);
        lenient().when(timeSupplier.getAsLong()).thenReturn(System.nanoTime());
    }

    @Test
    void willCallThreadFinishedThenTerminateWhenThreadIsNoLongerAlive() {
        when(threadHolder.isAlive()).thenReturn(false);

        assertThrows(InvalidEventHandlerException.class, threadMonitorHarness::action, "monitor should terminate with exception when monitored thread is no longer alive");
        verify(threadHolder).reportFinished();
    }

    @Test
    void willResetTimersOnFirstIteration() throws InvalidEventHandlerException {
        when(threadHolder.startedNS()).thenReturn(System.nanoTime());

        assertFalse(threadMonitorHarness.action(), "monitor should not report delay on first iteration while initializing timers");

        verify(threadHolder).resetTimers();
    }

    @Test
    void willAbortCheckingWhenLoopStartedTimeIsZero() throws InvalidEventHandlerException {
        when(threadHolder.startedNS()).thenReturn(0L);

        assertFalse(threadMonitorHarness.action(), "monitor should skip delay checking when loop has not yet recorded a start time");

        verify(threadHolder, never()).shouldLog(anyLong());
    }

    @Test
    void willAbortCheckingWhenLoopStartedTimeIsNotInALoop() throws InvalidEventHandlerException {
        when(threadHolder.startedNS()).thenReturn(NOT_IN_A_LOOP);

        assertFalse(threadMonitorHarness.action(), "monitor should skip delay checking when thread is not currently executing in a loop");

        verify(threadHolder, never()).shouldLog(anyLong());
    }

    @Test
    void willResetTimersWhenLoopStartedTimeHasChanged() throws InvalidEventHandlerException {
        AtomicLong loopStartedTime = new AtomicLong(System.nanoTime());
        when(threadHolder.startedNS()).thenAnswer(iom -> loopStartedTime.incrementAndGet());

        assertFalse(threadMonitorHarness.action(), "monitor should not report delay on first action call while detecting loop start time change");
        assertFalse(threadMonitorHarness.action(), "monitor should not report delay on subsequent action call when loop start time keeps changing");
        assertFalse(threadMonitorHarness.action(), "monitor should not report delay on subsequent action call when loop start time keeps changing");

        verify(threadHolder, times(3)).resetTimers();
    }

    @Test
    void willNotResetTimersWhenLoopStartedTimeHasNotChanged() throws InvalidEventHandlerException {
        when(threadHolder.startedNS()).thenReturn(System.nanoTime());

        assertFalse(threadMonitorHarness.action(), "monitor should not report delay on first iteration while initializing timers");
        assertFalse(threadMonitorHarness.action(), "monitor should not report delay on subsequent action call when loop start time remains stable");
        assertFalse(threadMonitorHarness.action(), "monitor should not report delay on subsequent action call when loop start time remains stable");

        verify(threadHolder, times(1)).resetTimers();
    }

    @Test
    void willCallMonitorThreadDelayedWhenDelayIsGreaterThanThreshold() throws InvalidEventHandlerException {
        final long firstCallTime = System.nanoTime();
        when(threadHolder.startedNS()).thenReturn(System.nanoTime());
        when(timeSupplier.getAsLong()).thenReturn(firstCallTime);

        // reset timers on first iteration
        threadMonitorHarness.action();

        long actionCallDelayNs = TIMING_TOLERANCE_NS + 1;

        when(timeSupplier.getAsLong()).thenReturn(firstCallTime + actionCallDelayNs);
        assertTrue(threadMonitorHarness.action(), "monitor should report delay when action call duration exceeds timing tolerance threshold");
        verify(threadHolder).monitorThreadDelayed(actionCallDelayNs);
    }

    @Test
    void willNotCallDumpThreadWhenShouldNotLog() throws InvalidEventHandlerException {
        final long nowTime = System.nanoTime();
        when(threadHolder.startedNS()).thenReturn(System.nanoTime());
        when(timeSupplier.getAsLong()).thenReturn(nowTime);

        // reset timers on first iteration
        threadMonitorHarness.action();

        when(threadHolder.shouldLog(nowTime)).thenReturn(false);
        assertFalse(threadMonitorHarness.action(), "monitor should not dump thread state when logging is suppressed by shouldLog policy");
        verify(threadHolder, never()).dumpThread(anyLong(), anyLong());
    }

    @Test
    void willCallDumpThreadWhenShouldLog() throws InvalidEventHandlerException {
        final long nowTime = System.nanoTime();
        final long loopStartedTime = System.nanoTime();
        when(threadHolder.startedNS()).thenReturn(loopStartedTime);
        when(timeSupplier.getAsLong()).thenReturn(nowTime);

        // reset timers on first iteration
        threadMonitorHarness.action();

        when(threadHolder.shouldLog(anyLong())).thenReturn(true);
        assertFalse(threadMonitorHarness.action(), "monitor should return false after dumping thread state to prevent continuous reporting");
        verify(threadHolder).dumpThread(loopStartedTime, nowTime);
    }
}
