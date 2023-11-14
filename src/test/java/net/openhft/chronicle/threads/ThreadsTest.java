/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ThreadsTest extends ThreadsTestCommon {

    @Test
    public void shouldDumpStackTracesForStuckDelegatedExecutors() {
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
    public void shouldDumpStackTracesForStuckDaemonDelegatedExecutors() {
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
    public void shouldDumpStackTracesForStuckNestedDelegatedExecutors() {
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
