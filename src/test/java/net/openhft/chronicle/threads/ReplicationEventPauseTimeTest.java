/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.testframework.process.JavaProcessBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationEventPauseTimeTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void configuresBothLoopTypesInAFreshJvm(int millis) throws InterruptedException {
        Process process = JavaProcessBuilder.create(Probe.class)
                .withJvmArguments("-DreplicationEventPauseTime=" + millis)
                .withProgramArguments(Integer.toString(millis)).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> JavaProcessBuilder.getProcessStdOut(process)
                    + JavaProcessBuilder.getProcessStdErr(process));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws IllegalAccessException {
            int millis = Integer.parseInt(args[0]);
            Pauser expected = millis == 0 ? Pauser.yielding() : Pauser.balancedUpToMillis(millis);
            checkLoops(EventGroup.builder(), expected, expected, millis);
            Pauser replication = new YieldingPauser(5);
            Pauser concurrent = new YieldingPauser(10);
            checkLoops(EventGroup.builder().withReplicationPauser(replication)
                    .withConcurrentPauserSupplier(() -> concurrent), replication, concurrent, millis);
        }

        private static void checkLoops(EventGroupBuilder builder, Pauser expectedReplication,
                                       Pauser expectedConcurrent, int millis) throws IllegalAccessException {
            try (EventGroup group = builder.withConcurrentThreadsNum(1)
                    .withPriorities(HandlerPriority.REPLICATION, HandlerPriority.CONCURRENT).build()) {
                group.addHandler(handler(HandlerPriority.REPLICATION));
                group.addHandler(handler(HandlerPriority.CONCURRENT));
                VanillaEventLoop replication = (VanillaEventLoop) Jvm.getField(EventGroup.class, "replication").get(group);
                List<?> loops = (List<?>) Jvm.getField(EventGroup.class, "concThreads").get(group);
                VanillaEventLoop concurrent = (VanillaEventLoop) loops.get(0);
                assertEquals(expectedReplication.toString(), replication.pauser.toString());
                assertEquals(expectedConcurrent.toString(), concurrent.pauser.toString());
                assertEquals(Math.max(1, millis), replication.timerIntervalMS());
                assertEquals(Math.max(1, millis), concurrent.timerIntervalMS());
            }
        }

        private static EventHandler handler(HandlerPriority priority) {
            return new EventHandler() {
                @Override
                public HandlerPriority priority() {
                    return priority;
                }

                @Override
                public boolean action() {
                    return false;
                }
            };
        }
    }
}
