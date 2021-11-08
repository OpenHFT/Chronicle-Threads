package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class EventGroupBadAffinityTest {
    private Map<ExceptionKey, Integer> exceptions;
    private final Map<Predicate<ExceptionKey>, String> expectedExceptions = new LinkedHashMap<>();

    @BeforeEach
    public void recordExceptions() {
        exceptions = Jvm.recordExceptions();
    }

    private void expectException(String message) {
        expectException(k -> k.message.contains(message) || (k.throwable != null && k.throwable.getMessage().contains(message)), message);
    }

    private void expectException(Predicate<ExceptionKey> predicate, String description) {
        expectedExceptions.put(predicate, description);
    }

    @AfterEach
    public void checkExceptions() {
        for (Map.Entry<Predicate<ExceptionKey>, String> expectedException : expectedExceptions.entrySet()) {
            if (!exceptions.keySet().removeIf(expectedException.getKey()))
                Slf4jExceptionHandler.WARN.on(getClass(), "No error for " + expectedException.getValue());
        }
        expectedExceptions.clear();
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            fail();
        }
    }

    @Timeout(5_000)
    @Test
    public void testInvalidAffinity() {
        expectException("Cannot parse 'xxx'");
        try (final EventLoop eventGroup = EventGroup.builder().withBinding("xxx").build()) {
            assertThrows(TimeoutException.class, eventGroup::start);
        }
    }
}
