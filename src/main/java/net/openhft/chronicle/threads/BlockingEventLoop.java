/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
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
 * Event loop suited for I/O or other long running tasks.
 * Each handler is executed on its own thread.
 *
 * <p>The {@link Pauser} supplied at construction is used to create a fresh
 * instance for every handler thread.  Idle handlers therefore pause
 * independently of one another.</p>
 *
 * <p>Calling {@link #start()} launches a thread for each added handler.
 * When {@link #stop()} is invoked those threads are interrupted and the
 * executor service is shut down.</p>
 *
 * <p>Handlers with priorities other than
 * {@link net.openhft.chronicle.core.threads.HandlerPriority#BLOCKING}
 * are accepted but treated the same as blocking handlers.</p>
 */
public class BlockingEventLoop extends AbstractLifecycleEventLoop implements EventLoop {

    @NotNull
    private transient final EventLoop parent;
    @NotNull
    private transient final ExecutorService service;
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();
    private final List<Runner> runners = new CopyOnWriteArrayList<>();
    private final NamedThreadFactory threadFactory;
    private final Supplier<Pauser> pauserSupplier;

    /**
     * Creates a blocking event loop using a parent loop and custom pauser supplier.
     *
     * @param parent parent event loop
     * @param name   loop name
     * @param pauser pauser supplier for handler threads
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
     * Creates a blocking event loop that manages its own threads with a balanced pauser.
     *
     * @param name loop name
     */
    public BlockingEventLoop(@NotNull final String name) {
        super(name);
        this.parent = this;
        this.threadFactory = new NamedThreadFactory(name, null, null, true);
        this.service = Executors.newCachedThreadPool(threadFactory);
        this.pauserSupplier = Pauser::balanced;
    }

    /**
     * Registers a new handler.  Every call spawns another thread for the
     * handler.
     * <p>Priorities other than
     * {@link net.openhft.chronicle.core.threads.HandlerPriority#BLOCKING}
     * are permitted but are not treated specially.</p>
     *
     * @param handler to execute
     */
    @Override
    public synchronized void addHandler(@NotNull final EventHandler handler) {
        if (DEBUG_ADDING_HANDLERS)
            Jvm.debug().on(getClass(), "Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");
        eventLoopQuietly(parent, handler);
        this.handlers.add(handler);
        if (isStarted())
            this.startHandler(handler);
    }

    @Override
    protected synchronized void performStart() {
        handlers.forEach(this::startHandler);
    }

    private void startHandler(final EventHandler handler) {
        try {
            final Runner runner = new Runner(handler, pauserSupplier.get());
            runners.add(runner);
            service.execute(runner);

        } catch (RejectedExecutionException e) {
            if (!service.isShutdown())
                Jvm.warn().on(getClass(), e);
        }
    }

    @Override
    public void unpause() {
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

    @SuppressWarnings("ForLoopReplaceableByForEach")
    @Override
    public boolean isRunningOnThread(Thread thread) {
        for (int i=0; i < runners.size(); i++) {
            if (thread == runners.get(i).thread()) {
                return true;
            }
        }
        return false;
    }

    private final class Runner implements Runnable {
        private final EventHandler handler;
        private final Pauser pauser;
        private boolean endedGracefully = false;
        private transient volatile Thread thread = null;

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

                while (isStarted()) {
                    if (handler.action())
                        pauser.reset();
                    else
                        pauser.pause();
                }
                endedGracefully = true;
            } catch (InvalidEventHandlerException e) {
                // expected and logged below.
            } catch (Throwable t) {
                if (!isClosed())
                    Jvm.warn().on(handler.getClass(), asString(handler) + " threw ", t);

            } finally {
                if (Jvm.isDebugEnabled(handler.getClass()))
                    Jvm.debug().on(handler.getClass(), "handler " + asString(handler) + " done.");
                loopFinishedQuietly(handler);
                if (!endedGracefully) {
                    // remove handler for clarity when debugging
                    if (DEBUG_REMOVING_HANDLERS)
                        Jvm.debug().on(getClass(), "Removing " + handler.priority() + " " + handler);
                    handlers.remove(handler);
                    closeQuietly(handler);
                }
                runners.remove(this);
            }
        }

        private String asString(final Object handler) {
            return Integer.toHexString(System.identityHashCode(handler));
        }

        public void unpause() {
            pauser.unpause();
        }

        public Thread thread() {
            return thread;
        }
    }
}
