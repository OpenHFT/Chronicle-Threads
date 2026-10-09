/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractReferenceCounted;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.onoes.ThreadLocalisedExceptionHandler;
import net.openhft.chronicle.core.threads.CleaningThreadLocal;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.core.time.SystemTimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class ThreadsTestCommonLifecycleTest {
    enum Scenario { SUCCESS, BODY_FAILURE, SETUP_FAILURE, AFTER_FAILURE, CLEANUP_FAILURE,
        ABORT, LEAK, BODY_AND_LEAK, ABORT_AND_LEAK, WARNING, MISSING_EVENT }

    private static State state;
    private static final CleaningThreadLocal<Object> LOCAL = CleaningThreadLocal.withCleanup(
            Object::new, ignored -> state.localCleaned = true);

    @AfterEach
    void releaseSentinel() {
        if (state != null && state.leak != null)
            state.leak.releaseLast();
        LOCAL.remove();
        Jvm.resetExceptionHandlers();
        AbstractReferenceCounted.disableReferenceTracing();
        SystemTimeProvider.CLOCK = SystemTimeProvider.INSTANCE;
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void preservesOutcomeAndAlwaysCleansUp(Scenario scenario) {
        state = new State(scenario);
        TestExecutionSummary result = execute(FixtureTestCase.class);
        assertEquals(1, result.getTestsStartedCount());
        assertEquals(1, state.cleanups);
        assertTrue(state.localCleaned);
        assertSame(SystemTimeProvider.INSTANCE, SystemTimeProvider.CLOCK);
        assertSame(Slf4jExceptionHandler.WARN, ThreadLocalisedExceptionHandler.unwrap(Jvm.warn()));
        if (scenario == Scenario.SUCCESS) {
            assertEquals(1, result.getTestsSucceededCount(), result.getFailures().toString());
        } else if (scenario == Scenario.ABORT || scenario == Scenario.ABORT_AND_LEAK) {
            assertEquals(1, result.getTestsAbortedCount(), result.getFailures().toString());
            assertEquals(0, result.getTestsFailedCount());
        } else {
            assertEquals(1, result.getTestsFailedCount(), result.getFailures().toString());
            Throwable failure = result.getFailures().get(0).getException();
            if (scenario != Scenario.LEAK && scenario != Scenario.WARNING && scenario != Scenario.MISSING_EVENT) {
                assertSame(state.primary, failure);
                assertEquals(0, failure.getSuppressed().length, "Resource checks must not follow the original failure");
            }
        }
    }

    @Test
    void failingResourceExtensionPreventsLeakVerification() {
        state = new State(Scenario.LEAK);
        TestExecutionSummary result = execute(FailingExtensionTestCase.class);
        assertEquals(1, result.getTestsFailedCount());
        assertSame(state.primary, result.getFailures().get(0).getException());
        assertEquals(0, state.primary.getSuppressed().length);
        assertEquals(1, state.cleanups);
    }

    @Test
    void timeoutRetainsItsCauseAndStillCleansUp() {
        state = new State(Scenario.BODY_AND_LEAK);
        TestExecutionSummary result = execute(TimeoutTestCase.class);
        assertEquals(1, result.getTestsFailedCount());
        assertTrue(result.getFailures().get(0).getException() instanceof java.util.concurrent.TimeoutException);
        assertEquals(1, state.cleanups);
    }

    @Test
    void followingTestHasNoStaleExpectations() {
        state = new State(Scenario.BODY_FAILURE);
        assertEquals(1, execute(FixtureTestCase.class).getTestsFailedCount());
        state = new State(Scenario.SUCCESS);
        assertEquals(1, execute(FixtureTestCase.class).getTestsSucceededCount());
    }

    private static TestExecutionSummary execute(Class<?> type) {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(type))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build(), listener);
        return listener.getSummary();
    }

    private static final class State {
        final Scenario scenario;
        final AssertionError primary = new AssertionError("original fixture failure");
        int cleanups;
        boolean localCleaned;
        ReferenceSentinel leak;
        State(Scenario scenario) { this.scenario = scenario; }
    }

    private static final class ReferenceSentinel extends AbstractReferenceCounted {
        @Override protected void performRelease() { }
    }

    public static class FixtureTestCase extends ThreadsTestCommon {
        @BeforeEach void setup() {
            LOCAL.get();
            SystemTimeProvider.CLOCK = new SetTimeProvider();
            if (state.scenario == Scenario.SETUP_FAILURE)
                throw state.primary;
            if (state.scenario == Scenario.MISSING_EVENT || state.scenario == Scenario.BODY_FAILURE)
                expectException("fixture sentinel");
        }

        @AfterEach void after() {
            if (state.scenario == Scenario.AFTER_FAILURE)
                throw state.primary;
        }

        @Test void body() {
            if (state.scenario == Scenario.LEAK || state.scenario == Scenario.BODY_AND_LEAK || state.scenario == Scenario.ABORT_AND_LEAK)
                state.leak = new ReferenceSentinel();
            if (state.scenario == Scenario.BODY_FAILURE || state.scenario == Scenario.BODY_AND_LEAK)
                throw state.primary;
            org.junit.jupiter.api.Assumptions.assumeFalse(state.scenario == Scenario.ABORT || state.scenario == Scenario.ABORT_AND_LEAK);
            if (state.scenario == Scenario.WARNING)
                Jvm.warn().on(getClass(), "fixture sentinel");
        }

        @Override void preAfter() {
            state.cleanups++;
            if (state.scenario == Scenario.CLEANUP_FAILURE)
                throw state.primary;
        }
    }

    public static class FailingCleanup implements AfterEachCallback {
        @Override public void afterEach(ExtensionContext context) { throw state.primary; }
    }

    @ExtendWith(FailingCleanup.class)
    public static class FailingExtensionTestCase extends FixtureTestCase { }

    public static class TimeoutTestCase extends FixtureTestCase {
        @Override @Test
        @org.junit.jupiter.api.Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
        void body() {
            state.leak = new ReferenceSentinel();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
