/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.SimpleCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.openhft.chronicle.threads.Threads.eventLoopQuietly;
import static net.openhft.chronicle.threads.Threads.loopFinishedQuietly;
import static net.openhft.chronicle.threads.Threads.loopStartedCall;

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

    @SuppressWarnings("java:S2065") // Chronicle Wire honours transient runtime fields without Serializable.
    private final transient ExecutorService service;
    @SuppressWarnings("java:S2065") // Avoid traversing the parent loop during reflective marshalling.
    private final transient EventLoop parent;
    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();
    private final Pauser pauser;
    private transient volatile Thread thread = null;
    //! Shutdown callback bookkeeping is runtime state, not Wire configuration.
    //! Compatibility control: Chronicle-Wire's MarshallingEventGroupTest.test.
    private transient boolean handlersFinished;

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
    protected synchronized void performStart() {
        //! Stop may win before task submission; it then owns finishing the pending handlers.
        //! Regression: HandlerAdmissionTest.acceptedBeforeStartIsFinished.
        if (!isStopped())
            service.execute(this);
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
        //! Exclude a racing task submission before taking the never-started finish snapshot.
        //! Regression: HandlerAdmissionTest.acceptedBeforeStartIsFinished.
        synchronized (this) {
            service.shutdownNow();
        }
        Threads.shutdownDaemon(service);
        finishHandlersOnce(true);
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
     * Stopping loops finish and close unused handlers; fully closed loops reject as before.
     * Use {@link #addHandlerOrThrow(EventHandler)} to retain ownership after rejection.
     */
    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        //! Legacy shutdown races must retire unused handlers without reporting a registration failure.
        //! Regressions: HandlerAdmissionTest.legacyRegistrationRetiresLateHandler and legacyCleanupRunsOutsideAdmissionLock.
        if (!tryAddHandler(handler))
            Threads.retireUnadmittedHandler(handler);
    }

    //! Checked rejection does not wrap or finish the caller's handler.
    //! Regression: HandlerAdmissionTest.checkedRegistrationRetainsRejectedOwnership.
    @Override
    public void addHandlerOrThrow(@NotNull EventHandler handler) throws HandlerRegistrationRejectedException {
        if (!tryAddHandler(handler))
            throw new HandlerRegistrationRejectedException("Event loop is stopping or closed: " + name());
    }

    private synchronized boolean tryAddHandler(EventHandler handler) {
        if (isStopped() || isClosing() || handlersFinished)
            return false;

        if (EventLoop.DEBUG_ADDING_HANDLERS)
            Jvm.debug().on(getClass(), "Adding " + handler.priority() + " " + handler + " to " + this.name);
        eventLoopQuietly(parent, handler);
        if (!handlers.contains(handler))
            handlers.add(new IdempotentLoopStartedEventHandler(handler));
        return true;
    }

    @Override
    public void run() {
        //! Claim the task under the same lock as stop's final snapshot so a cancelled start cannot finish twice.
        //! Regression: HandlerAdmissionTest.acceptedBeforeStartIsFinished.
        synchronized (this) {
            if (handlersFinished)
                return;
            thread = Thread.currentThread();
        }

        try {
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
            finishHandlersOnce(false);
            thread = null;
        }
    }

    //! Stop-before-start still owes accepted handlers their finish callback; callbacks must not hold admission locks.
    //! Regressions: HandlerAdmissionTest.acceptedBeforeStartIsFinished and legacyCleanupRunsOutsideAdmissionLock.
    private void finishHandlersOnce(boolean onlyIfNotRunning) {
        final List<EventHandler> snapshot;
        synchronized (this) {
            if (handlersFinished || (onlyIfNotRunning && thread != null))
                return;
            handlersFinished = true;
            snapshot = new ArrayList<>(handlers);
        }
        snapshot.forEach(Threads::loopFinishedQuietly);
    }

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
            if (EventLoop.DEBUG_REMOVING_HANDLERS)
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

        @SuppressWarnings("java:S2065") // Exclude live handler resources from reflective marshalling.
        private final transient EventHandler eventHandler;
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
