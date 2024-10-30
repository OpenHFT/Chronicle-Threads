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
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An abstract base class for managing the lifecycle of an {@link EventLoop}.
 * <p>
 * This class:
 * <ul>
 *     <li>Enforces the life-cycle stages of an EventLoop</li>
 *     <li>Implements idempotency for {@link #start()} and {@link #stop()}</li>
 *     <li>Ensures {@link #stop()} only returns once the EventLoop is stopped</li>
 * </ul>
 * For further details on the life-cycle, see {@link EventLoopLifecycle}.
 */
@SuppressWarnings("this-escape")
public abstract class AbstractLifecycleEventLoop extends AbstractCloseable implements EventLoop {

    /**
     * Maximum duration (in milliseconds) to wait in {@link #awaitTermination()} before logging an error.
     * This timeout is designed to prevent indefinite blocking during tests.
     */
    private static final long AWAIT_TERMINATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    /**
     * The current lifecycle state of the EventLoop.
     */
    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);

    /**
     * Name of the event loop, used for identification and logging.
     */
    protected final String name;

    /**
     * Constructs a new {@code AbstractLifecycleEventLoop} with the given name.
     *
     * @param name The name of the event loop, which will have trailing slashes removed.
     */
    protected AbstractLifecycleEventLoop(@NotNull String name) {
        this.name = name.replaceAll("/$", "");
        // Disables the single-threaded check for this instance.
        singleThreadedCheckDisabled(true);
    }

    /**
     * Returns the name with an appended slash.
     *
     * @return the name suffixed with a slash if non-empty
     */
    protected String nameWithSlash() {
        return withSlash(name);
    }

    @Override
    public final void start() {
        throwExceptionIfClosed();

        // Transition to STARTED state if currently NEW and performs start actions.
        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STARTED)) {
            performStart();
        }
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * Starts the event loop. This method should only be called once per instance.
     */
    protected abstract void performStart();

    @Override
    public final void stop() {
        // Transitions from NEW or STARTED states to STOPPING and performs stop actions.
        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STOPPING)) {
            performStopFromNew();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else if (lifecycle.compareAndSet(EventLoopLifecycle.STARTED, EventLoopLifecycle.STOPPING)) {
            performStopFromStarted();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else {
            awaitTermination();
        }
    }

    /**
     * Implements stopping behavior from the {@link EventLoopLifecycle#NEW} state.
     * This method should ensure that all {@link EventHandler#loopFinished()} calls are completed.
     */
    protected abstract void performStopFromNew();

    /**
     * Implements stopping behavior from the {@link EventLoopLifecycle#STARTED} state.
     * Ensures handlers finish their final iteration and call {@link EventHandler#loopFinished()}.
     */
    protected abstract void performStopFromStarted();

    /**
     * Waits until the EventLoop has stopped or a timeout occurs.
     * If the timeout expires, logs an error message and returns.
     */
    protected final void awaitTermination() {
        long endTime = System.currentTimeMillis() + AWAIT_TERMINATION_TIMEOUT_MS;
        while (!Thread.currentThread().isInterrupted()) {
            if (lifecycle.get() == EventLoopLifecycle.STOPPED)
                return;
            if (System.currentTimeMillis() > endTime) {
                Jvm.error().on(getClass(), "awaitTermination() timed out, continuing. This probably represents a bug.");
            }
            Jvm.pause(1);
        }
        if (lifecycle.get() != EventLoopLifecycle.STOPPED) {
            Jvm.warn().on(getClass(), "awaitTermination() interrupted, returning in state " + lifecycle.get());
        }
    }

    @Override
    protected void performClose() {
        stop();
    }

    @Override
    protected void assertCloseable() {
        // Ensures the EventLoop is not closed from within its own thread.
        if (isRunningOnThread(Thread.currentThread())) {
            throw new ThreadingIllegalStateException("Attempting to close " + name + " from within!", null);
        }
    }

    /**
     * Checks if the EventLoop is running on the specified thread.
     *
     * @param thread The thread to check
     * @return {@code true} if the EventLoop is running on the specified thread, {@code false} otherwise
     */
    public abstract boolean isRunningOnThread(Thread thread);

    /**
     * Checks if the EventLoop is in the STARTED state.
     *
     * @return {@code true} if the EventLoop has started, {@code false} otherwise
     */
    protected boolean isStarted() {
        return lifecycle.get() == EventLoopLifecycle.STARTED;
    }

    @Override
    public boolean isStopped() {
        return lifecycle.get().isStopped();
    }

    /**
     * Appends a slash to the provided name if non-empty.
     *
     * @param n The string to append a slash to if not empty
     * @return the modified string with a trailing slash
     */
    static String withSlash(String n) {
        return n.isEmpty() ? n : n + "/";
    }
}
