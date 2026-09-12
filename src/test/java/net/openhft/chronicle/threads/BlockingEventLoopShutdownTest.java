/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingEventLoopShutdownTest extends ThreadsTestCommon {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("try") // The close call itself is one of the operations under test.
    void runnerCanFinishDuringThreadCheck(boolean closeLoop) throws IllegalAccessException {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final RemovingRunnerList runners = new RemovingRunnerList(finish);
        try (BlockingEventLoop loop = new BlockingEventLoop("finishing-runner")) {
            // Intercept collection reads, without changing how the real runner removes itself.
            Jvm.getField(BlockingEventLoop.class, "runners").set(loop, runners);
            assertFalse(loop.isRunningOnThread(Thread.currentThread()));
            loop.addHandler(() -> {
                worker.set(Thread.currentThread());
                started.countDown();
                await(finish);
                throw InvalidEventHandlerException.reusable();
            });
            loop.start();
            try {
                await(started);
                assertTrue(loop.isRunningOnThread(worker.get()));
                runners.armed.set(true);
                if (closeLoop) {
                    loop.close();
                    assertTrue(loop.isClosed());
                } else {
                    assertFalse(loop.isRunningOnThread(Thread.currentThread()));
                }
                assertTrue(runners.removed.getCount() == 0);
                assertFalse(loop.isRunningOnThread(worker.get()));
            } finally {
                finish.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker did not reach the required lifecycle state");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for the worker", e);
        }
    }

    private static final class RemovingRunnerList extends CopyOnWriteArrayList<Object> {
        private static final long serialVersionUID = 1L;
        private final transient CountDownLatch finish;
        private final transient CountDownLatch removed = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean();

        private RemovingRunnerList(CountDownLatch finish) {
            this.finish = finish;
        }

        @Override
        public int size() {
            final int size = super.size();
            finishRunner();
            return size;
        }

        @Override
        public Iterator<Object> iterator() {
            final Iterator<Object> snapshot = super.iterator();
            finishRunner();
            return snapshot;
        }

        private void finishRunner() {
            if (armed.compareAndSet(true, false)) {
                finish.countDown();
                await(removed);
            }
        }

        @Override
        public boolean remove(Object runner) {
            final boolean result = super.remove(runner);
            if (result)
                removed.countDown();
            return result;
        }
    }
}
