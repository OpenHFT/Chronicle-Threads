package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.AbstractReferenceCounted;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.threads.CleaningThread;
import net.openhft.chronicle.core.threads.ThreadDump;
import org.junit.After;
import org.junit.Before;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static java.lang.String.format;
import static org.junit.Assert.fail;

public class ThreadsTestCommon {
    private ThreadDump threadDump;
    private Map<ExceptionKey, Integer> exceptions;
    private Map<Predicate<ExceptionKey>, String> expectedExceptions = new LinkedHashMap<>();

    @Before
    public void enableReferenceTracing() {
        AbstractReferenceCounted.enableReferenceTracing();
    }

    public void assertReferencesReleased() {
        AbstractReferenceCounted.assertReferencesReleased();
    }

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @Before
    public void recordExceptions() {
        exceptions = Jvm.recordExceptions();
    }

    public void expectException(String message) {
        expectException(k -> k.message.contains(message) || (k.throwable != null && k.throwable.getMessage().contains(message)), message);
    }

    public void expectException(Predicate<ExceptionKey> predicate, String description) {
        expectedExceptions.put(predicate, description);
    }

    public void checkExceptions() {
        for (Map.Entry<Predicate<ExceptionKey>, String> expectedException : expectedExceptions.entrySet()) {
            if (!exceptions.keySet().removeIf(expectedException.getKey()))
                Slf4jExceptionHandler.WARN.on(getClass(), "No error for " + expectedException.getValue());
        }
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            fail();
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

    @After
    public void afterChecks() {
        CleaningThread.performCleanup(Thread.currentThread());
        System.gc();
        AbstractCloseable.waitForCloseablesToClose(100);
        assertReferencesReleased();
        checkThreadDump();
        checkExceptions();
    }
}
