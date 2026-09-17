/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.testframework.Waiters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.openhft.chronicle.threads.TestEventHandlers.CountingHandler;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class HandlerAdmissionTest extends ThreadsTestCommon {
    enum LoopType {
        MEDIUM, VANILLA, BLOCKING, MONITOR, GROUP;

        AbstractLifecycleEventLoop create() {
            switch (this) {
                case MEDIUM: return new MediumEventLoop(null, "admission", Pauser.balanced(), true, "none");
                case VANILLA: return new VanillaEventLoop(null, "admission", Pauser.balanced(), 10, true,
                        "none", VanillaEventLoop.ALLOWED_PRIORITIES);
                case BLOCKING: return new BlockingEventLoop("admission");
                case MONITOR: return new MonitorEventLoop(null, "admission", Pauser.balanced());
                default: return EventGroup.builder().withName("admission").withDaemon(true).build();
            }
        }

        HandlerPriority priority() {
            return this == MONITOR ? HandlerPriority.MONITOR : HandlerPriority.MEDIUM;
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void legacyRegistrationRetiresLateHandler(LoopType type) throws Exception {
        for (boolean started : new boolean[]{false, true}) {
            CountingHandler handler = new CountingHandler(type.priority());
            try (AbstractLifecycleEventLoop loop = type.create()) {
                if (started)
                    loop.start();
                loop.stop();
                assertDoesNotThrow(() -> loop.addHandler(handler));
                assertRetired(handler);
                assertNull(handler.eventLoop());
            }
            assertRetired(handler);
        }
    }

    @ParameterizedTest
    @EnumSource(HandlerPriority.class)
    void legacyGroupRetiresEveryConfiguredPriority(HandlerPriority priority) {
        CountingHandler handler = new CountingHandler(priority);
        try (EventGroup group = EventGroup.builder().withDaemon(true).build()) {
            group.stop();
            group.addHandler(handler);
            assertRetired(handler);
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    @SuppressWarnings("try")
    void checkedRegistrationRetainsRejectedOwnership(LoopType type) throws Exception {
        for (boolean closed : new boolean[]{false, true}) {
            CountingHandler handler = new CountingHandler(type.priority());
            try (AbstractLifecycleEventLoop loop = type.create()) {
                if (closed)
                    loop.close();
                else
                    loop.stop();
                assertThrows(HandlerRegistrationRejectedException.class, () -> loop.addHandlerOrThrow(handler));
                assertEquals(0, handler.loopFinishedCalled());
                assertEquals(0, handler.closeCalled());
                assertNull(handler.eventLoop());
            } finally {
                handler.loopFinished();
                handler.close();
            }
            assertRetired(handler);
        }
    }

    @ParameterizedTest
    @EnumSource(HandlerPriority.class)
    void checkedRegistrationAcceptsConfiguredPriorities(HandlerPriority priority) throws Exception {
        CountingHandler handler = new CountingHandler(priority);
        try (EventGroup group = EventGroup.builder().withDaemon(true).build()) {
            group.addHandlerOrThrow(handler);
            group.stop();
            assertEquals(1, handler.loopFinishedCalled());
            assertEquals(0, handler.actionCalled());
        }
        assertRetired(handler);
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void acceptedBeforeStartIsFinished(LoopType type) throws Exception {
        CountingHandler handler = new CountingHandler(type.priority());
        try (AbstractLifecycleEventLoop loop = type.create()) {
            loop.addHandlerOrThrow(handler);
            loop.stop();
            assertEquals(1, handler.loopFinishedCalled());
        }
        assertRetired(handler);
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void stoppingRegistrationHasExplicitOwnership(LoopType type) throws Exception {
        CountDownLatch finishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> gateFailure = new AtomicReference<>();
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try (AbstractLifecycleEventLoop loop = type.create()) {
            loop.addHandler(new CountingHandler(type.priority()) {
                @Override
                public void loopFinished() {
                    super.loopFinished();
                    finishing.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Stop gate timed out");
                    } catch (Throwable failure) {
                        gateFailure.set(failure);
                    }
                }
            });
            Future<?> stopped = stopper.submit(loop::stop);
            try {
                assertTrue(finishing.await(5, TimeUnit.SECONDS));
                assertFalse(stopped.isDone(), "Registration must happen during STOPPING");
                CountingHandler legacy = new CountingHandler(type.priority());
                loop.addHandler(legacy);
                assertRetired(legacy);
                CountingHandler checked = new CountingHandler(type.priority());
                assertThrows(HandlerRegistrationRejectedException.class, () -> loop.addHandlerOrThrow(checked));
                assertEquals(0, checked.loopFinishedCalled());
                assertEquals(0, checked.closeCalled());
                checked.loopFinished();
                checked.close();
            } finally {
                release.countDown();
                stopped.get(5, TimeUnit.SECONDS);
            }
            assertNull(gateFailure.get(), () -> "Stop gate failed: " + gateFailure.get());
        } finally {
            release.countDown();
            stopper.shutdownNow();
            assertTrue(stopper.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    void legacyCleanupRunsOutsideAdmissionLock(LoopType type) throws Exception {
        ExecutorService registrar = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountingHandler nested = new CountingHandler(type.priority());
        try (AbstractLifecycleEventLoop loop = type.create()) {
            loop.stop();
            CountingHandler outer = new CountingHandler(type.priority()) {
                @Override
                public void loopFinished() {
                    super.loopFinished();
                    try {
                        registrar.submit(() -> loop.addHandler(nested)).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        failure.set(e);
                    }
                }
            };
            loop.addHandler(outer);
            assertNull(failure.get(), () -> "Cleanup held the admission lock: " + failure.get());
            assertRetired(outer);
            assertRetired(nested);
        } finally {
            registrar.shutdownNow();
            assertTrue(registrar.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupFailuresRemainVisible() {
        expectException("finish diagnostic control");
        expectException("close diagnostic control");
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM) {
            @Override
            public void loopFinished() {
                super.loopFinished();
                throw new IllegalStateException("finish diagnostic control");
            }

            @Override
            public void close() throws IOException {
                super.close();
                throw new IOException("close diagnostic control");
            }
        };
        try (EventGroup group = EventGroup.builder().build()) {
            group.stop();
            group.addHandler(handler);
            assertRetired(handler);
        }
    }

    @Test
    void configurationAndCallbackFailuresRemainVisible() throws Exception {
        CountingHandler unsupported = new CountingHandler(HandlerPriority.MEDIUM);
        HandlerRegistrationClosedException original = new HandlerRegistrationClosedException("priority callback control");
        EventHandler throwingPriority = new CountingHandler(HandlerPriority.MEDIUM) {
            @Override
            public HandlerPriority priority() {
                throw original;
            }
        };
        try (EventGroup group = EventGroup.builder().withPriorities(HandlerPriority.BLOCKING).build();
             EventGroup timerGroup = EventGroup.builder().withPriorities(HandlerPriority.TIMER).build();
             VanillaEventLoop vanilla = new VanillaEventLoop(null, "configured", Pauser.balanced(), 1,
                     true, "none", java.util.EnumSet.of(HandlerPriority.TIMER))) {
            group.stop();
            timerGroup.stop();
            assertThrows(IllegalStateException.class, () -> timerGroup.addHandler(unsupported));
            assertThrows(IllegalStateException.class, () -> timerGroup.addHandlerOrThrow(unsupported));
            assertThrows(IllegalStateException.class, () -> group.addHandlerOrThrow(unsupported));
            assertThrows(IllegalStateException.class, () -> group.addHandler(unsupported));
            assertThrows(IllegalStateException.class, () -> vanilla.addHandlerOrThrow(unsupported));
            assertSame(original, assertThrows(HandlerRegistrationClosedException.class,
                    () -> group.addHandlerOrThrow(throwingPriority)));
            assertSame(original, assertThrows(HandlerRegistrationClosedException.class,
                    () -> group.addHandler(throwingPriority)));
            assertEquals(0, unsupported.loopFinishedCalled());
            assertEquals(0, unsupported.closeCalled());
        } finally {
            unsupported.close();
        }
    }

    @Test
    void stoppedGroupDoesNotCreateLazyLoops() {
        AtomicInteger pausersCreated = new AtomicInteger();
        try (EventGroup group = EventGroup.builder().withConcurrentPauserSupplier(() -> {
            pausersCreated.incrementAndGet();
            return Pauser.balanced();
        }).build()) {
            group.stop();
            for (HandlerPriority priority : new HandlerPriority[]{HandlerPriority.CONCURRENT, HandlerPriority.REPLICATION}) {
                CountingHandler legacy = new CountingHandler(priority);
                group.addHandler(legacy);
                assertRetired(legacy);
                assertThrows(HandlerRegistrationRejectedException.class,
                        () -> group.addHandlerOrThrow(new CountingHandler(priority)));
            }
            assertEquals(0, pausersCreated.get());
            assertNull(Jvm.getValue(group, "replication"));
            List<?> concurrent = Jvm.getValue(group, "concThreads");
            assertTrue(concurrent.stream().allMatch(java.util.Objects::isNull));
        }
    }

    @Test
    void lazyRegistrationRacingStopKeepsOwnership() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountingHandler handler = new CountingHandler(HandlerPriority.CONCURRENT);
        try (EventGroup group = EventGroup.builder().withConcurrentThreadsNum(1).withConcurrentPauserSupplier(() -> {
            creating.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Timed out waiting to finish child creation");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return Pauser.balanced();
        }).build()) {
            Future<Boolean> admitted = workers.submit(() -> {
                try {
                    group.addHandlerOrThrow(handler);
                    return true;
                } catch (HandlerRegistrationRejectedException rejected) {
                    return false;
                }
            });
            try {
                assertTrue(creating.await(5, TimeUnit.SECONDS));
                Future<?> stopped = workers.submit(group::stop);
                Waiters.waitForCondition("Stop did not begin", group::isStopped, 5_000);
                release.countDown();
                boolean owned = admitted.get(5, TimeUnit.SECONDS);
                stopped.get(5, TimeUnit.SECONDS);
                List<VanillaEventLoop> concurrent = Jvm.getValue(group, "concThreads");
                assertTrue(concurrent.get(0).isStopped(), "Lazy child escaped group shutdown");
                assertEquals(owned ? 1 : 0, handler.loopFinishedCalled());
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            if (handler.closeCalled() == 0) {
                handler.loopFinished();
                handler.close();
            }
        }
        assertRetired(handler);
    }

    @Test
    void customLoopMustExplicitlySupportCheckedAdmission() {
        AtomicInteger registrations = new AtomicInteger();
        try (AbstractLifecycleEventLoop custom = new AbstractLifecycleEventLoop("custom") {
            @Override public void addHandler(EventHandler handler) { registrations.incrementAndGet(); }
            @Override protected void performStart() { }
            @Override protected void performStopFromNew() { }
            @Override protected void performStopFromStarted() { }
            @Override public boolean isAlive() { return false; }
            @Override public void unpause() { }
            @Override public boolean isRunningOnThread(Thread thread) { return false; }
        }) {
            CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
            assertThrows(UnsupportedOperationException.class, () -> custom.addHandlerOrThrow(handler));
            assertEquals(0, registrations.get());
            assertEquals(0, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
        }
    }

    @Test
    void checkedExceptionMustBeCaughtOrDeclared(@TempDir Path directory) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Run the API contract test with a JDK");
        assertFalse(RuntimeException.class.isAssignableFrom(HandlerRegistrationRejectedException.class));
        String prefix = "import net.openhft.chronicle.threads.*; import net.openhft.chronicle.core.threads.*; ";
        String[] bodies = {
                "void register(EventGroup g, EventHandler h) { g.addHandlerOrThrow(h); }",
                "void register(EventGroup g, EventHandler h) throws HandlerRegistrationRejectedException { g.addHandlerOrThrow(h); }",
                "void register(EventGroup g, EventHandler h) { try { g.addHandlerOrThrow(h); } catch (HandlerRegistrationRejectedException e) { h.loopFinished(); } }"
        };
        for (int i = 0; i < bodies.length; i++) {
            Path source = directory.resolve("Caller" + i + ".java");
            Files.write(source, (prefix + "class Caller" + i + " { " + bodies[i] + " }").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int result = compiler.run(null, null, errors, "-proc:none", "-classpath",
                    System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
            if (i == 0) {
                assertNotEquals(0, result, "Unhandled checked rejection compiled");
                assertTrue(errors.toString("UTF-8").contains("HandlerRegistrationRejectedException"));
            } else {
                assertEquals(0, result, errors.toString("UTF-8"));
            }
        }
    }

    private static void assertRetired(CountingHandler handler) {
        assertAll(() -> assertEquals(0, handler.loopStartedCalled()),
                () -> assertEquals(0, handler.actionCalled()),
                () -> assertEquals(1, handler.loopFinishedCalled()),
                () -> assertEquals(1, handler.closeCalled()));
    }
}
