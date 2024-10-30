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
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.threads.Threads.*;

/**
 * An implementation of {@link EventLoop} designed to handle blocking tasks, providing
 * support for multiple {@link EventHandler}s to execute concurrently in separate threads.
 */
public class BlockingEventLoop extends AbstractLifecycleEventLoop implements EventLoop {

    @NotNull
    private transient final EventLoop parent;

    /**
     * Executor service to handle task execution, initialized with a cached thread pool.
     */
    @NotNull
    private transient final ExecutorService service;

    /**
     * A thread-safe list holding the event handlers assigned to this event loop.
     */
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();

    /**
     * A thread-safe list of runner instances managing individual event handler execution.
     */
    private final List<Runner> runners = new CopyOnWriteArrayList<>();

    /**
     * Factory for naming and creating threads for the executor service.
     */
    private final NamedThreadFactory threadFactory;

    /**
     * Supplier for creating a {@link Pauser} for managing handler pauses.
     */
    private final Supplier<Pauser> pauserSupplier;

    /**
     * Constructs a BlockingEventLoop with a specified parent event loop, name, and pauser supplier.
     *
     * @param parent The parent event loop
     * @param name   The name of the event loop
     * @param pauser Supplier to create a Pauser for handler pause management
     */
    public BlockingEventLoop(@NotNull final EventLoop parent,
                             @NotNull final String name,
                             @NotNull final Supplier<Pauser> pauser) {
        super(name);
        this.parent = parent;
        this.threadFactory = new NamedThreadFactory(name, null, null, true);
        this.service = Executors.newCachedThreadPool(threadFactory);
        this.pauserSupplier = pauser;
    }

    /**
     * Constructs a BlockingEventLoop with no parent, using itself as the parent event loop.
     * The default {@link Pauser} supplier is used.
     *
     * @param name The name of the event loop
     */
    public BlockingEventLoop(@NotNull final String name) {
        super(name);
        this.parent = this;
        this.threadFactory = new NamedThreadFactory(name, null, null, true);
        this.service = Executors.newCachedThreadPool(threadFactory);
        this.pauserSupplier = Pauser::balanced;
    }

    /**
     * Adds an {@link EventHandler} to the event loop. Each handler is executed in its own thread.
     *
     * @param handler The event handler to be added
     */
    @Override
    public synchronized void addHandler(@NotNull final EventHandler handler) {
        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");

        // Add handler to the parent event loop quietly and start if the loop is running
        eventLoopQuietly(parent, handler);
        this.handlers.add(handler);
        if (isStarted())
            this.startHandler(handler);
    }

    @Override
    protected synchronized void performStart() {
        handlers.forEach(this::startHandler);
    }

    /**
     * Initiates the execution of a handler in a separate thread managed by the executor service.
     *
     * @param handler The handler to start execution for
     */
    private void startHandler(final EventHandler handler) {
        try {
            final Runner runner = new Runner(handler, pauserSupplier.get());
            runners.add(runner);
            service.submit(runner);

        } catch (RejectedExecutionException e) {
            if (!service.isShutdown())
                Jvm.warn().on(getClass(), e);
        }
    }

    @Override
    public void unpause() {
        // Unpauses each runner in the event loop
        runners.forEach(Runner::unpause);
        unpark(service);
    }

    @Override
    protected void performStopFromNew() {
        shutdownExecutorService();
    }

    @Override
    protected void performStopFromStarted() {
        shutdownExecutorService();
    }

    /**
     * Shuts down the executor service immediately, interrupting all tasks.
     */
    private void shutdownExecutorService() {
        /*
         * It's necessary for blocking handlers to be interrupted, so they abort what they're
         * doing and run to completion immediately.
         */
        service.shutdownNow();
        unpause();
        Threads.shutdown(service);
    }

    @Override
    public boolean isAlive() {
        return !service.isShutdown();
    }

    @Override
    protected void performClose() {
        super.performClose();
        closeQuietly(handlers);
        runners.clear();
    }

    @Override
    public String toString() {
        return "BlockingEventLoop{" +
                "name=" + name +
                '}';
    }

    @Override
    public boolean isRunningOnThread(Thread thread) {
        // Checks if the provided thread is associated with any runner in this loop
        for (int i=0; i < runners.size(); i++) {
            if (thread == runners.get(i).thread()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Internal runner class to manage individual handler execution.
     */
    private final class Runner implements Runnable {
        private final EventHandler handler;
        private final Pauser pauser;
        private boolean endedGracefully = false;
        private transient volatile Thread thread = null;

        /**
         * Constructs a runner for the specified handler and pauser.
         *
         * @param handler The handler managed by this runner
         * @param pauser  The pauser to control handler pauses
         */
        public Runner(final EventHandler handler, Pauser pauser) {
            this.handler = handler;
            this.pauser = pauser;
        }

        @Override
        public void run() {
            try {
                throwExceptionIfClosed();
                thread = Thread.currentThread();
                handler.loopStarted();

                // Executes handler actions, pausing or resetting the pauser based on action results
                while (isStarted()) {
                    if (handler.action())
                        pauser.reset();
                    else
                        pauser.pause();
                }
                endedGracefully = true;
            } catch (InvalidEventHandlerException e) {
                // Expected exception, no action needed
            } catch (Throwable t) {
                if (!isClosed())
                    Jvm.warn().on(handler.getClass(), asString(handler) + " threw ", t);

            } finally {
                // Cleanup after handler completes
                if (Jvm.isDebugEnabled(handler.getClass()))
                    Jvm.debug().on(handler.getClass(), "handler " + asString(handler) + " done.");
                loopFinishedQuietly(handler);
                if (!endedGracefully) {
                    // remove handler for clarity when debugging
                    handlers.remove(handler);
                    closeQuietly(handler);
                }
                runners.remove(this);
            }
        }

        /**
         * Returns a string representation of the handler's memory identity hash code.
         *
         * @param handler The handler to represent
         * @return The string representation of the handler
         */
        private String asString(final Object handler) {
            return Integer.toHexString(System.identityHashCode(handler));
        }

        /**
         * Unpauses the associated pauser.
         */
        public void unpause() {
            pauser.unpause();
        }

        /**
         * Retrieves the thread running this runner.
         *
         * @return The current thread
         */
        public Thread thread() {
            return thread;
        }
    }
}
