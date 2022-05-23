package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class EventGroupBadAffinityTest extends ThreadsTestCommon {

    @Timeout(5_000)
    @Test
    public void testInvalidAffinity() {
        expectException("Cannot parse 'xxx'");
        ignoreException("Timed out waiting for start!");
        try (final EventLoop eventGroup = EventGroup.builder().withBinding("xxx").build()) {
            assertThrows(TimeoutException.class, eventGroup::start);
        }
    }
}
