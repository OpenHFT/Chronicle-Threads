/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.InvalidMarshallableException;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.onoes.ExceptionHandler;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the helper routines in {@link EventLoops} and the life-cycle
 * checks in {@link EventLoop}.
 * <p>
 * The tests confirm that {@link EventLoops#stopAll(Object...)} accepts
 * {@code null} values and waits for every loop to stop. They also verify
 * that calling {@link EventLoop#close()} from the loop's own thread triggers
 * a {@link ThreadingIllegalStateException}.
 */
class EventLoopsTest extends ThreadsTestCommon {

    @Test
    void stopAllCanHandleNulls() {
        final StringBuilder sb = new StringBuilder();
        final ExceptionHandler eh = (c, m, t) -> sb.append(m);
        ExceptionHandler exceptionHandler = Jvm.warn();
        try {
            Jvm.setWarnExceptionHandler(eh);
            EventLoops.stopAll(null, Arrays.asList(null, null, null), null);
            // Should silently accept nulls
            assertTrue(sb.toString().isEmpty(), "stopAll ignores nulls without warning");
        } finally {
            Jvm.setWarnExceptionHandler(exceptionHandler);
        }
    }

    @Timeout(5_000)
    @Test
    void stopAllWillBlockUntilTheLastEventLoopStops() {
        try (final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "test", Pauser.balanced(), false, "none");
             final BlockingEventLoop blockingEventLoop = new BlockingEventLoop("blocker")) {
            assertTrue(doTest(blockingEventLoop, mediumEventLoop), "stopAll completed after blocked loop was released");
        }
    }

    private static boolean doTest(BlockingEventLoop blockingEventLoop, MediumEventLoop mediumEventLoop) {
        blockingEventLoop.start();
        mediumEventLoop.start();

        Semaphore semaphore = new Semaphore(0);
        blockingEventLoop.addHandler(() -> {
            semaphore.acquireUninterruptibly();
            return false;
        });
        while (!semaphore.hasQueuedThreads()) {
            Jvm.pause(10);
        }

        AtomicBoolean stoppedEm = new AtomicBoolean(false);
        Thread stopAllThread = new Thread(() -> {
            EventLoops.stopAll(mediumEventLoop, Arrays.asList(null, Collections.singleton(blockingEventLoop)));
            stoppedEm.set(true);
        });
        stopAllThread.start();
        long blockUntilMs = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < blockUntilMs) {
            assertFalse(stoppedEm.get(), "stopAll blocks until blocked loop is released");
        }
        semaphore.release();
        long stopAllTimeoutMs = System.currentTimeMillis() + 5_000;
        while (!stoppedEm.get() && System.currentTimeMillis() < stopAllTimeoutMs) {
            Jvm.pause(1);
        }
        try {
            stopAllThread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return stoppedEm.get();
    }

    private static Stream<EventLoop> eventLoopsToClose() {
        return Stream.of(
                new MediumEventLoop(null, "medium", Pauser.balanced(), false, null),
                new BlockingEventLoop("blocking")
        );
    }

    @ParameterizedTest
    @MethodSource("eventLoopsToClose")
    void closeFromEventLoopThreadThrowsException(EventLoop el) {
        try {
            AtomicBoolean exceptionThrownInHandler = new AtomicBoolean();
            AtomicBoolean eventHandlerFinished = new AtomicBoolean();

            EventHandler closingEventHandler = new EventHandler() {
                @Override
                public boolean action() throws InvalidEventHandlerException, InvalidMarshallableException {
                    try {
                        el.close();
                        return true;
                    } catch (ThreadingIllegalStateException e) {
                        exceptionThrownInHandler.set(true);
                        throw InvalidEventHandlerException.reusable();
                    }
                }

                @Override
                public void loopFinished() {
                    eventHandlerFinished.set(true);
                }
            };

            el.addHandler(closingEventHandler);
            el.start();

            long timeoutTime = System.currentTimeMillis() + 500;
            while (!exceptionThrownInHandler.get()) {
                if (System.currentTimeMillis() > timeoutTime) {
                    Assertions.fail("Event loop " + el.name() + " didn't " + (eventHandlerFinished.get() ? "throw an exception when attempting to close" : "run in this time"));
                }
                Jvm.pause(10);
            }

            assertTrue(el.isAlive(), "event loop remains alive after close attempt from its own thread");
            assertFalse(el.isStopped(), "event loop not stopped after close attempt from its own thread");
            assertFalse(el.isClosed(), "event loop not closed after close attempt from its own thread");
            assertFalse(el.isClosing(), "event loop not closing after close attempt from its own thread");
        } finally {
            el.close();

            assertTrue(el.isClosed(), "event loop closed in cleanup");
        }

    }
}
