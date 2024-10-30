/*
 * Copyright 2016-2020 chronicle.software
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
import net.openhft.chronicle.core.annotation.HotMethod;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.openhft.chronicle.threads.Threads.*;

/**
 * The {@code MonitorEventLoop} class manages and monitors a set of {@link EventHandler}s, running them in a loop.
 * This class uses a {@link Pauser} to handle pause and resume functionality and is designed to be run on a
 * dedicated single-threaded executor service.
 */
public class MonitorEventLoop extends AbstractLifecycleEventLoop implements Runnable, EventLoop {
    public static final String MONITOR_INITIAL_DELAY = "MonitorInitialDelay";
    static int MONITOR_INITIAL_DELAY_MS = Jvm.getInteger(MONITOR_INITIAL_DELAY, 10_000);

    private transient final ExecutorService service;  // Single-threaded executor for running the loop
    private transient final EventLoop parent;         // Optional parent event loop
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();  // List of event handlers to monitor
    private final Pauser pauser;  // Pauser for managing loop pausing and unpausing
    private transient volatile Thread thread = null;  // Thread currently running the event loop

    /**
     * Constructs a new {@code MonitorEventLoop} with the specified parent and pauser.
     *
     * @param parent the parent event loop
     * @param pauser the pauser to use for managing the event loop's pauses
     */
    public MonitorEventLoop(final EventLoop parent, final Pauser pauser) {
        this(parent, "", pauser);
    }

    /**
     * Constructs a new {@code MonitorEventLoop} with a specified parent, name, and pauser.
     *
     * @param parent the parent event loop
     * @param name   the name of this event loop
     * @param pauser the pauser to use for managing the event loop's pauses
     */
    public MonitorEventLoop(final EventLoop parent, final String name, final Pauser pauser) {
        super(name + (withSlash(parent == null ? "" : parent.name())) + "event~loop~monitor");
        this.parent = parent;
        this.pauser = pauser;
        service = Executors.newSingleThreadExecutor(
                new NamedThreadFactory(name, true, null, true));
    }

    /**
     * Starts the event loop by submitting it to the executor service.
     */
    @Override
    protected void performStart() {
        service.submit(this);
    }

    /**
     * Unpauses the event loop by signaling the pauser.
     */
    @Override
    public void unpause() {
        pauser.unpause();
    }

    /**
     * Stops the event loop, shutting down the executor service.
     */
    @Override
    protected void performStopFromNew() {
        performStop();
    }

    @Override
    protected void performStopFromStarted() {
        performStop();
    }

    private void performStop() {
        unpause();
        Threads.shutdownDaemon(service);
    }

    /**
     * Checks if the event loop is currently alive.
     *
     * @return {@code true} if the event loop is started, {@code false} otherwise
     */
    @Override
    public boolean isAlive() {
        return isStarted();
    }

    /**
     * Adds a new event handler to the loop, ensuring it is initialized to start once the loop begins.
     *
     * @param handler the handler to add
     */
    @Override
    public synchronized void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");
        eventLoopQuietly(parent, handler);
        if (!handlers.contains(handler))
            handlers.add(new IdempotentLoopStartedEventHandler(handler));
    }

    /**
     * Runs the event loop, processing each handler repeatedly until the loop is stopped or interrupted.
     */
    @Override
    @HotMethod
    public void run() {
        throwExceptionIfClosed();

        try {
            thread = Thread.currentThread();
            // Initial delay before starting monitoring
            final long waitUntilMs = System.currentTimeMillis() + MONITOR_INITIAL_DELAY_MS;
            while (System.currentTimeMillis() < waitUntilMs && isStarted())
                pauser.pause();
            pauser.reset();
            while (isStarted() && !Thread.currentThread().isInterrupted()) {
                boolean busy = runHandlers();
                pauser.pause();
                if (busy)
                    pauser.reset();
            }
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), e);
        } finally {
            synchronized (this) {
                handlers.forEach(Threads::loopFinishedQuietly);
            }
        }
    }

    /**
     * Processes each handler in the loop, handling exceptions as necessary and removing any invalid handlers.
     *
     * @return {@code true} if any handler performed work, {@code false} otherwise
     */
    @HotMethod
    private boolean runHandlers() {
        boolean busy = false;
        for (int i = 0; i < handlers.size(); i++) {
            final EventHandler handler = handlers.get(i);
            try {
                if (loopStartedCall(this, handler)) {
                    removeHandler(i--);
                    continue;
                }
                busy |= handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(i--);
            } catch (Exception e) {
                Jvm.warn().on(getClass(), "Exception thrown by handler " + handler, e);
                removeHandler(i--);
            }
        }
        return busy;
    }

    /**
     * Removes a handler from the list, performing any necessary cleanup.
     *
     * @param handlerIndex the index of the handler to remove
     */
    private synchronized void removeHandler(int handlerIndex) {
        try {
            EventHandler removedHandler = handlers.remove(handlerIndex);
            loopFinishedQuietly(removedHandler);
            Closeable.closeQuietly(removedHandler);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (!handlers.isEmpty()) {
                Jvm.warn().on(MonitorEventLoop.class, "Error removing handler!");
            }
        }
    }

    /**
     * Closes all handlers in the event loop.
     */
    @Override
    protected void performClose() {
        super.performClose();
        net.openhft.chronicle.core.io.Closeable.closeQuietly(handlers);
    }

    /**
     * Checks if the specified thread is the event loop's running thread.
     *
     * @param thread the thread to check
     * @return {@code true} if the specified thread is the event loop's thread, {@code false} otherwise
     */
    @Override
    public boolean isRunningOnThread(Thread thread) {
        return this.thread == thread;
    }

    /**
     * Decorator class that ensures {@link EventHandler#loopStarted()} is called only once for each handler
     * before it starts executing in the event loop.
     */
    private static final class IdempotentLoopStartedEventHandler extends AbstractCloseable implements EventHandler {

        private transient final EventHandler eventHandler;
        private final String handler;
        private boolean loopStarted = false;

        public IdempotentLoopStartedEventHandler(@NotNull EventHandler eventHandler) {
            this.eventHandler = eventHandler;
            handler = eventHandler.toString();
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            return eventHandler.action();
        }

        @Override
        public void eventLoop(EventLoop eventLoop) {
            eventHandler.eventLoop(eventLoop);
        }

        @Override
        public void loopStarted() {
            if (!loopStarted) {
                loopStarted = true;
                eventHandler.loopStarted();
            }
        }

        @Override
        public void loopFinished() {
            eventHandler.loopFinished();
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return eventHandler.priority();
        }

        @Override
        public boolean equals(Object o) {
            return eventHandler.equals(o);
        }

        @Override
        public int hashCode() {
            return eventHandler.hashCode();
        }

        @Override
        protected void performClose() throws IllegalStateException {
            Closeable.closeQuietly(eventHandler);
        }

        @Override
        public String toString() {
            return "IdempotentLoopStartedEventHandler{" +
                    "handler=" + handler +
                    '}';
        }
    }

    /**
     * Returns a string representation of the {@code MonitorEventLoop} with its properties.
     *
     * @return a string representing this {@code MonitorEventLoop}
     */
    @Override
    public String toString() {
        return "MonitorEventLoop{" +
                "service=" + service +
                ", parent=" + parent +
                ", handlers=" + handlers +
                ", pauser=" + pauser +
                ", name='" + name + '\'' +
                '}';
    }
}
