/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.AbstractReferenceCounted;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.threads.CleaningThread;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.core.time.SystemTimeProvider;
import net.openhft.chronicle.core.util.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(ThreadsTestCommon.ResourceVerification.class)
public class ThreadsTestCommon {
    private final Map<Predicate<ExceptionKey>, String> ignoreExceptions = new LinkedHashMap<>();
    private final Map<Predicate<ExceptionKey>, String> expectedExceptions = new LinkedHashMap<>();
    private ThreadDump threadDump;
    private Map<ExceptionKey, Integer> exceptions;
    private boolean referenceTracingEnabled;

    public static final class ResourceVerification implements AfterEachCallback {
        @Override
        public void afterEach(ExtensionContext context) {
            ThreadsTestCommon fixture = context.getRequiredTestInstances().findInstance(ThreadsTestCommon.class)
                    .orElseThrow(() -> new IllegalStateException("Missing Threads test fixture"));
            Throwable failure = attempt(null, fixture::afterChecks);
            Throwable primary = context.getExecutionException().orElse(null);
            if (primary == null && failure == null)
                failure = attempt(null, fixture::verifySuccessfulTest);
            failure = attempt(failure, fixture::resetTestState);
            if (failure != null) {
                if (primary != null && !(primary instanceof org.opentest4j.TestAbortedException))
                    primary.addSuppressed(failure);
                else
                    throw Jvm.rethrow(failure);
            }
        }
    }

    @BeforeEach
    public void beforeEachThreadsTestCommon() {
        enableReferenceTracing();
        threadDump();
        recordExceptions();
    }

    public void enableReferenceTracing() {
        AbstractReferenceCounted.enableReferenceTracing();
        referenceTracingEnabled = true;
    }

    private void assertReferencesReleased() {
        AbstractReferenceCounted.assertReferencesReleased();
    }

    public void threadDump() {
        threadDump = new ThreadDump();
    }

    private void checkThreadDump() {
        if (threadDump != null)
            threadDump.assertNoNewThreads();
    }

    public void recordExceptions() {
        exceptions = Jvm.recordExceptions();
    }

    void ignoreException(String message) {
        ignoreException(k -> contains(k.message, message) || (k.throwable != null && k.throwable.getMessage().contains(message)), message);
    }

    private static boolean contains(String text, String message) {
        return text != null && text.contains(message);
    }

    void expectException(String message) {
        expectException(k -> contains(k.message, message) || (k.throwable != null && contains(k.throwable.getMessage(), message)), message);
    }

    private void ignoreException(Predicate<ExceptionKey> predicate, String description) {
        ignoreExceptions.put(predicate, description);
    }

    private void expectException(Predicate<ExceptionKey> predicate, String description) {
        expectedExceptions.put(predicate, description);
    }

    private void checkExceptions() {
        for (Map.Entry<Predicate<ExceptionKey>, String> expectedException : expectedExceptions.entrySet()) {
            if (!exceptions.keySet().removeIf(expectedException.getKey()))
                throw new AssertionError("No error for " + expectedException.getValue());
        }
        expectedExceptions.clear();
        for (Map.Entry<Predicate<ExceptionKey>, String> expectedException : ignoreExceptions.entrySet()) {
            if (!exceptions.keySet().removeIf(expectedException.getKey()))
                Slf4jExceptionHandler.DEBUG.on(getClass(), "No error for " + expectedException.getValue());
        }
        ignoreExceptions.clear();
        for (String msg : "Shrinking ,Allocation of , ms to add mapping for ,jar to the classpath, ms to pollDiskSpace for , us to linearScan by position from ,File released ,Overriding roll length from existing metadata, was 3600000, overriding to 86400000   ".split(",")) {
            exceptions.keySet().removeIf(e -> e.message.contains(msg));
        }
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            throw new AssertionError(exceptions.keySet());
        }
    }

    void assertExceptionThrown(String message) {
        String description = format("No exception found containing string `%s`", message);
        assertExceptionThrown(k -> k.message.contains(message) || (k.throwable != null && k.throwable.getMessage().contains(message)), description);
    }

    private void assertExceptionThrown(Predicate<ExceptionKey> predicate, String description) {
        for (ExceptionKey key : exceptions.keySet()) {
            if (predicate.test(key)) {
                return;
            }
        }
        fail(description);
    }

    public void afterChecks() throws InterruptedException {
        Throwable failure = attempt(null, this::preAfter);
        failure = attempt(failure, ThreadsTestCommon::resetSystemTimeProviderClock);
        failure = attempt(failure, () -> CleaningThread.performCleanup(Thread.currentThread()));
        if (failure != null)
            throw Jvm.rethrow(failure);
    }

    private void verifySuccessfulTest() {
        if (exceptions != null)
            checkExceptions();
        System.gc();
        Throwable failure = attempt(null, () -> AbstractCloseable.waitForCloseablesToClose(1000));
        if (referenceTracingEnabled)
            failure = attempt(failure, this::assertReferencesReleased);
        failure = attempt(failure, this::checkThreadDump);
        if (failure != null)
            throw Jvm.rethrow(failure);
    }

    private void resetTestState() {
        Throwable failure = attempt(null, Jvm::resetExceptionHandlers);
        if (referenceTracingEnabled)
            failure = attempt(failure, AbstractReferenceCounted::disableReferenceTracing);
        referenceTracingEnabled = false;
        threadDump = null;
        exceptions = null;
        expectedExceptions.clear();
        ignoreExceptions.clear();
        resetSystemTimeProviderClock();
        if (failure != null)
            throw Jvm.rethrow(failure);
    }

    private static Throwable attempt(Throwable failure, ThrowingRunnable<Throwable> action) {
        try {
            action.run();
        } catch (Throwable next) {
            if (failure == null)
                return next;
            if (failure != next)
                failure.addSuppressed(next);
        }
        return failure;
    }

    void preAfter() throws InterruptedException {
    }

    /**
     * Test-only helper to adjust the initial monitor delay in a single place.
     * This keeps static mutations out of instance lifecycle methods for SpotBugs.
     */
    protected static void setMonitorInitialDelayMs(int delayMillis) {
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = delayMillis;
    }

    /**
     * Resets the global SystemTimeProvider clock to the default instance.
     */
    protected static void resetSystemTimeProviderClock() {
        SystemTimeProvider.CLOCK = SystemTimeProvider.INSTANCE;
    }
}
