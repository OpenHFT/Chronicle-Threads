/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.HotMethod;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.SimpleCloseable;
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
 * Event loop dedicated to low-frequency monitoring tasks. Handlers added to this loop are
 * expected to use {@link HandlerPriority#MONITOR} so they do not interfere with application
 * work. The provided {@link Pauser} determines how often the handlers are polled and is reset
 * whenever a handler reports activity.
 *
 * <p>The loop waits for {@link #MONITOR_INITIAL_DELAY_MS} milliseconds after startup before
 * invoking any handlers.</p>
 */
public class MonitorEventLoop extends AbstractLifecycleEventLoop implements Runnable, EventLoop {
    public static final String MONITOR_INITIAL_DELAY = "MonitorInitialDelay";
    static int MONITOR_INITIAL_DELAY_MS = Jvm.getInteger(MONITOR_INITIAL_DELAY, 10_000);

    private transient final ExecutorService service;
    private transient final EventLoop parent;
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();
    private final Pauser pauser;
    private transient volatile Thread thread = null;

    public MonitorEventLoop(final EventLoop parent, final Pauser pauser) {
        this(parent, "", pauser);
    }

    public MonitorEventLoop(final EventLoop parent, final String name, final Pauser pauser) {
        super(name + (withSlash(parent == null ? "" : parent.name())) + "event~loop~monitor");
        this.parent = parent;
        this.pauser = pauser;
        service = Executors.newSingleThreadExecutor(
                new NamedThreadFactory(name, true, null, true));
    }

    @Override
    protected void performStart() {
        service.submit(this);
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

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

    @Override
    public boolean isAlive() {
        return isStarted();
    }

    /**
     * Registers a monitoring handler. The handler should have
     * {@link HandlerPriority#MONITOR} priority. It is wrapped in an
     * {@link IdempotentLoopStartedEventHandler} so that its
     * {@link EventHandler#loopStarted()} method runs exactly once on this
     * loop's thread. Adding the same handler twice is ignored.
     */
    @Override
    public synchronized void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        if (DEBUG_ADDING_HANDLERS)
            Jvm.debug().on(getClass(), "Adding " + handler.priority() + " " + handler + " to " + this.name);
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed");
        eventLoopQuietly(parent, handler);
        if (!handlers.contains(handler))
            handlers.add(new IdempotentLoopStartedEventHandler(handler));
    }

    @Override
    @HotMethod
    public void run() {
        throwExceptionIfClosed();

        try {
            thread = Thread.currentThread();
            // don't do any monitoring for the first MONITOR_INITIAL_DELAY_MS ms
            final long waitUntilMs = System.currentTimeMillis() + MONITOR_INITIAL_DELAY_MS;
            while (System.currentTimeMillis() < waitUntilMs && isStarted())
                pauser.pause();
            pauser.reset();
            while (isStarted() && !Thread.currentThread().isInterrupted()) {
                boolean busy;
                busy = runHandlers();
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

    private synchronized void removeHandler(int handlerIndex) {
        try {
            EventHandler removedHandler = handlers.remove(handlerIndex);
            loopFinishedQuietly(removedHandler);
            Closeable.closeQuietly(removedHandler);
            if (DEBUG_REMOVING_HANDLERS)
                Jvm.debug().on(getClass(), "Removing " + removedHandler.priority() + " " + removedHandler + " from " + this.name);
        } catch (ArrayIndexOutOfBoundsException e) {
            if (!handlers.isEmpty()) {
                Jvm.warn().on(MonitorEventLoop.class, "Error removing handler!");
            }
        }
    }

    @Override
    protected void performClose() {
        super.performClose();

        net.openhft.chronicle.core.io.Closeable.closeQuietly(handlers);
    }

    @Override
    public boolean isRunningOnThread(Thread thread) {
        return this.thread == thread;
    }

    /**
     * Decorator that invokes {@link EventHandler#loopStarted()} exactly once on
     * the loop thread before any calls to {@link EventHandler#action()}. The
     * monitor event loop wraps every handler in this class and calls
     * {@link #loopStarted()} at the beginning of each iteration.
     */
    private static final class IdempotentLoopStartedEventHandler extends SimpleCloseable implements EventHandler {

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
