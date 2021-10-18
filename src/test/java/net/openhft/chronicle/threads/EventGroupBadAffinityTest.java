package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public class EventGroupBadAffinityTest {
    private Map<ExceptionKey, Integer> exceptions;
    private final Map<Predicate<ExceptionKey>, String> expectedExceptions = new LinkedHashMap<>();

    @Before
    public void recordExceptions() {
        exceptions = Jvm.recordExceptions();
    }

    private void expectException(String message) {
        expectException(k -> k.message.contains(message) || (k.throwable != null && k.throwable.getMessage().contains(message)), message);
    }

    private void expectException(Predicate<ExceptionKey> predicate, String description) {
        expectedExceptions.put(predicate, description);
    }

    @After
    public void checkExceptions() {
        for (Map.Entry<Predicate<ExceptionKey>, String> expectedException : expectedExceptions.entrySet()) {
            if (!exceptions.keySet().removeIf(expectedException.getKey()))
                Slf4jExceptionHandler.WARN.on(getClass(), "No error for " + expectedException.getValue());
        }
        expectedExceptions.clear();
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            Assert.fail();
        }
    }

    @Test(timeout = 5000, expected = TimeoutException.class)
    public void testInvalidAffinity() {
        expectException("Cannot parse 'xxx'");
        try (final EventLoop eventGroup = EventGroup.builder().withBinding("xxx").build()) {
            eventGroup.start();
        }
    }
}
