/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreadsTest extends ThreadsTestCommon {

    @Test
    void shouldDumpStackTracesForStuckDelegatedExecutors() {
        final AtomicBoolean running = new AtomicBoolean(true);
        final ExecutorService service = Executors.newSingleThreadExecutor(new NamedThreadFactory("non-daemon-test"));
        service.submit(() -> {
            while (running.get()) {
                Jvm.pause(10L);
            }
        });

        Threads.shutdown(service);
        running.set(false);
        expectException("*** FAILED TO TERMINATE java.util.concurrent.Executors$");
        expectException("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
        assertExceptionThrown("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
    }

    @Test
    void shouldDumpStackTracesForStuckDaemonDelegatedExecutors() {
        final AtomicBoolean running = new AtomicBoolean(true);
        final ExecutorService service = Executors.newSingleThreadExecutor(new NamedThreadFactory("daemon-test"));
        service.submit(() -> {
            while (running.get()) {
                Jvm.pause(10L);
            }
        });

        Threads.shutdownDaemon(service);
        running.set(false);
        expectException("*** FAILED TO TERMINATE java.util.concurrent.Executors$");
        expectException("**** THE main/daemon-test THREAD DID NOT SHUTDOWN ***");
        assertExceptionThrown("**** THE main/daemon-test THREAD DID NOT SHUTDOWN ***");
    }

    @Test
    void shouldDumpStackTracesForStuckNestedDelegatedExecutors() {
        final AtomicBoolean running = new AtomicBoolean(true);
        final ExecutorService service = Executors.unconfigurableExecutorService(
                Executors.unconfigurableExecutorService(
                        Executors.unconfigurableExecutorService(
                                Executors.newSingleThreadExecutor(new NamedThreadFactory("non-daemon-test"))
                        )
                )
        );
        service.submit(() -> {
            while (running.get()) {
                Jvm.pause(10L);
            }
        });

        Threads.shutdown(service);
        running.set(false);
        expectException("*** FAILED TO TERMINATE java.util.concurrent.Executors$");
        expectException("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
        assertExceptionThrown("**** THE main/non-daemon-test THREAD DID NOT SHUTDOWN ***");
    }

    @Test
    void testRenderStackTrace() {
        StackTraceElement[] stackTrace = new StackTraceElement[]{
                new StackTraceElement("com.test.Something", "doSomething", "Something.java", 123),
                new StackTraceElement("com.test.SomethingElse", "doSomethingElse", "SomethingElse.java", 456),
                new StackTraceElement("com.test.SomethingElseAgain", "doSomethingElseAgain", "SomethingElseAgain.java", 789),
        };
        StringBuilder stringBuilder = new StringBuilder();
        Threads.renderStackTrace(stringBuilder, stackTrace);
        assertEquals(
                "  com.test.Something.doSomething(Something.java:123)\n" +
                        "  com.test.SomethingElse.doSomethingElse(SomethingElse.java:456)\n" +
                        "  com.test.SomethingElseAgain.doSomethingElseAgain(SomethingElseAgain.java:789)\n",
                stringBuilder.toString());
    }
}
