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
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.AbstractReferenceCounted;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.threads.CleaningThread;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.ThreadDump;
import net.openhft.chronicle.core.time.SystemTimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.fail;

public class ThreadsTestCommon {
    private final Map<Predicate<ExceptionKey>, String> ignoreExceptions = new LinkedHashMap<>();
    private Map<Predicate<ExceptionKey>, String> expectedExceptions = new LinkedHashMap<>();
    private ThreadDump threadDump;
    private Map<ExceptionKey, Integer> exceptions;

    static boolean contains(String text, String message) {
        return text != null && text.contains(message);
    }

    @BeforeEach
    public void enableReferenceTracing() {
        AbstractReferenceCounted.enableReferenceTracing();
    }

    public void assertReferencesReleased() {
        AbstractReferenceCounted.assertReferencesReleased();
    }

    @BeforeEach
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @BeforeEach
    public void recordExceptions() {
        exceptions = Jvm.recordExceptions();
    }

    public void ignoreException(String message) {
        ignoreException(k -> contains(k.message, message) || (k.throwable != null && k.throwable.getMessage().contains(message)), message);
    }

    public void expectException(String message) {
        expectException(k -> contains(k.message, message) || (k.throwable != null && contains(k.throwable.getMessage(), message)), message);
    }

    public void ignoreException(Predicate<ExceptionKey> predicate, String description) {
        ignoreExceptions.put(predicate, description);
    }

    public void expectException(Predicate<ExceptionKey> predicate, String description) {
        expectedExceptions.put(predicate, description);
    }

    public void checkExceptions() {
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

    public void assertExceptionThrown(String message) {
        String description = format("No exception found containing string `%s`", message);
        assertExceptionThrown(k -> k.message.contains(message) || (k.throwable != null && k.throwable.getMessage().contains(message)), description);
    }

    public void assertExceptionThrown(Predicate<ExceptionKey> predicate, String description) {
        for (ExceptionKey key : exceptions.keySet()) {
            if (predicate.test(key)) {
                return;
            }
        }
        fail(description);
    }

    @AfterEach
    public void afterChecks() throws InterruptedException {
        preAfter();
        SystemTimeProvider.CLOCK = SystemTimeProvider.INSTANCE;
        CleaningThread.performCleanup(Thread.currentThread());

        System.gc();
        AbstractCloseable.waitForCloseablesToClose(1000);
        assertReferencesReleased();
        checkEventLoops();
        checkThreadDump();
        checkExceptions();

        tearDown();
    }

    private void checkEventLoops() {
        List<EventLoop> eventLoops = new ArrayList<>();
        EventLoops.copyEventLoopsTo(eventLoops);

        for (EventLoop eventLoop : eventLoops) {
            if (!eventLoop.isStopped()) {
                final String message = eventLoop.toString();
                eventLoop.close();
                fail(message + " Not stopped");
            }
        }
    }

    protected void preAfter() throws InterruptedException {
    }

    protected void tearDown() {
    }
}
