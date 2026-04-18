/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EventGroupBadAffinityTest extends ThreadsTestCommon {

    /**
     * Ensures that an invalid CPU affinity string fails fast so that
     * misconfigured deployments do not run with unexpected processor binding.
     */
    @Timeout(5_000)
    @Test
    void testInvalidAffinity() {
        expectException("Cannot parse 'xxx'");
        ignoreException("Timed out waiting for start!");
        try (final EventLoop eventGroup = EventGroup.builder().withBinding("xxx").build()) {
            assertThrows(TimeoutException.class, eventGroup::start);
        }
    }
}
