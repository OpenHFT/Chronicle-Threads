/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ThreadMonitorsTest {

    @Test
    void forThreadLogsWhenEnabled() throws InvalidEventHandlerException {
        RecordingConsumer consumer = new RecordingConsumer();
        AtomicBoolean enabled = new AtomicBoolean(true);
        ThreadMonitor monitor = ThreadMonitors.forThread(
                "loop",
                1_000_000L,
                new DeterministicLongSupplier(-5_000_000L, -5_000_000L),
                Thread::currentThread,
                enabled::get,
                consumer
        );

        boolean result = monitor.action();

        assertFalse(result);
        assertEquals(1, consumer.messages.size());
        assertTrue(consumer.messages.get(0).contains("loop"));
    }

    @Test
    void forThreadSkipsLoggingWhenDisabled() throws InvalidEventHandlerException {
        List<String> messages = new ArrayList<>();
        AtomicBoolean enabled = new AtomicBoolean(false);
        ThreadMonitor monitor = ThreadMonitors.forThread(
                "loop",
                1_000_000L,
                new DeterministicLongSupplier(-5_000_000L, -5_000_000L),
                Thread::currentThread,
                enabled::get,
                messages::add
        );

        boolean result = monitor.action();

        assertFalse(result);
        assertTrue(messages.isEmpty());
    }

    private static final class DeterministicLongSupplier implements LongSupplier {
        private final long[] values;
        private int index;

        DeterministicLongSupplier(long... values) {
            this.values = values;
        }

        @Override
        public long getAsLong() {
            if (index >= values.length) {
                return values[values.length - 1];
            }
            return values[index++];
        }
    }

    private static final class RecordingConsumer implements Consumer<String> {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void accept(String message) {
            messages.add(message);
        }
    }
}
