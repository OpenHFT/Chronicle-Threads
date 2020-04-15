/*
 * Copyright (c) 2016-2019 Chronicle Software Ltd
 */

package net.openhft.chronicle.threads;

import net.openhft.affinity.AffinityLock;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.StackTrace;
import net.openhft.chronicle.core.annotation.HotMethod;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.Time;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.threads.internal.ThreadsUtil.ACCEPT_HANDLER_MOD_COUNT;

public class MediumEventLoop implements EventLoop, Runnable, Closeable {
    private static final boolean CHECK_INTERRUPTS = !Boolean.getBoolean("chronicle.eventLoop" +
            ".ignoreInterrupts");
    private static final Logger LOG = LoggerFactory.getLogger(MediumEventLoop.class);
    private static final EventHandler[] NO_EVENT_HANDLERS = {};
    private static final long FINISHED = Long.MAX_VALUE - 1;

    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    private final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    private final AtomicReference<EventHandler> newHandler = new AtomicReference<>();
    private final Pauser pauser;
    private final boolean daemon;
    private final String name;
    private final String binding;
    @NotNull
    private final AtomicBoolean running = new AtomicBoolean();

    @NotNull
    private EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    private volatile long loopStartMS;
    @Nullable
    private volatile Thread thread = null;
    @Nullable
    private volatile Throwable closedHere = null;
    private volatile boolean closed;

    /**
     * @param parent  the parent event loop
     * @param name    the name of this event hander
     * @param pauser  the pause strategy
     * @param daemon  is a demon thread
     * @param binding set affinity description, "any", "none", "1", "last-1"
     */
    public MediumEventLoop(final EventLoop parent,
                           final String name,
                           final Pauser pauser,
                           final boolean daemon,
                           final String binding) {
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.daemon = daemon;
        this.binding = binding;
        loopStartMS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon));
    }

    public static void closeAll(@NotNull final List<EventHandler> handlers) {
        handlers.forEach(h -> {
            closeQuietly(h);
            // do not remove the handler here, remove all at end instead
        });
    }

    @Nullable
    public Thread thread() {
        return thread;
    }

    @Override
    public void awaitTermination() {
        try {
            service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @NotNull
    @Override
    public String toString() {
        return "MediumEventLoop{" +
                "name='" + name + '\'' +
                ", parent=" + parent +
                ", service=" + service +
                ", mediumHandlers=" + mediumHandlers +
                ", newHandler=" + newHandler +
                ", pauser=" + pauser +
                ", closedHere=" + closedHere +
                '}';
    }

    @Override
    public void start() {
        checkClosed();
        if (!running.getAndSet(true))
            try {
                service.submit(this);
            } catch (RejectedExecutionException e) {
                if (!isClosed()) {
                    closeAll();
                    throw e;
                }
            }
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        final HandlerPriority priority = handler.priority();
        if (DEBUG_ADDING_HANDLERS)
            System.out.println("Adding " + priority + " " + handler + " to " + this.name);
        if (priority.alias() != HandlerPriority.MEDIUM)
            throw new IllegalStateException(name() + ": Unexpected priority " + priority + " for " + handler);
        checkClosed();
        checkInterrupted();

        if (thread == null || thread == Thread.currentThread()) {
            addNewHandler(handler);
            return;
        }
        do {
            pauser.unpause();
            checkClosed();
            checkInterrupted();
        } while (!newHandler.compareAndSet(null, handler));
    }

    void checkClosed() {
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed", closedHere);
    }

    void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Event Group has been interrupted");
    }

    public long loopStartMS() {
        return loopStartMS;
    }

    @Override
    @HotMethod
    public void run() {
        try (AffinityLock lock = AffinityLock.acquireLock(binding)) {
            thread = Thread.currentThread();
            runLoop();
        } catch (InvalidEventHandlerException e) {
            // ignore, already closed
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), "Loop terminated due to exception", e);
        } finally {
            loopFinishedAllHandlers();
            loopStartMS = FINISHED;
        }
    }

    private void loopFinishedAllHandlers() {
        mediumHandlers.forEach(EventHandler::loopFinished);
    }

    private void runLoop() throws InvalidEventHandlerException {
        int acceptNewHandlers = 0;
        while (running.get() && isNotInterrupted()) {
            if (isClosed()) {
                throw new InvalidEventHandlerException();
            }
            boolean busy = runMediumLoopOnly();

            if (busy) {
                pauser.reset();
                /*
                 * This is used for preventing starvation for new event handlers.
                 * Each modulo, potentially new event handlers are added even though
                 * there might be other handlers that are busy.
                 */
                if (acceptNewHandlers++ % ACCEPT_HANDLER_MOD_COUNT == 0) {
                    acceptNewHandlers();
                }
            } else {
                if (acceptNewHandlers())
                    continue;

                // reset the loop timeout.
                loopStartMS = Long.MAX_VALUE;
                pauser.pause();
            }
        }
    }

    private boolean isNotInterrupted() {
        return !CHECK_INTERRUPTS || !Thread.currentThread().isInterrupted();
    }

    private boolean runMediumLoopOnly() {
        loopStartMS = Time.currentTimeMillis();
        return runAllMediumHandler();
    }

    private void closeAll() {
        closeAllHandlers();
        LOG.trace("Remaining handlers");
        dumpRunningHandlers();
    }


    @HotMethod
    private boolean runAllMediumHandler() {
        boolean busy = false;
        final EventHandler[] mediumHandlersArray = this.mediumHandlersArray;
        for (EventHandler handler : mediumHandlersArray) {
            try {
                busy |= handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
    }

    private void removeHandler(final EventHandler handler) {
        removeHandler(handler, mediumHandlers);
        this.mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
    }

    private void removeHandler(final EventHandler handler, @NotNull final List<EventHandler> handlers) {
        try {
            handlers.remove(handler);
            handler.loopFinished();
        } catch (ArrayIndexOutOfBoundsException e2) {
            if (!handlers.isEmpty())
                throw e2;
        }
        closeQuietly(handler);
    }

    @HotMethod
    private boolean acceptNewHandlers() {
        boolean busy = false;
        final EventHandler handler = newHandler.getAndSet(null);
        if (handler != null) {
            addNewHandler(handler);
            busy = true;
        }
        return busy;
    }

    private void addNewHandler(@NotNull final  EventHandler handler) {
        final HandlerPriority t1 = handler.priority();
        switch (t1 == null ? HandlerPriority.MEDIUM : t1.alias()) {
            case REPLICATION:
            case CONCURRENT:
            case MEDIUM:
                if (!mediumHandlers.contains(handler)) {
                    mediumHandlers.add(handler);
                    mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
                }
                break;

            default:
                throw new IllegalArgumentException("Cannot add a " + handler.priority() + " task to a busy waiting thread");
        }
        handler.eventLoop(parent);
    }

    public String name() {
        return name;
    }

    public void dumpRunningState(@NotNull final String message, @NotNull final BooleanSupplier finalCheck) {
        final Thread thread = this.thread;
        if (thread == null) return;
        final StringBuilder out = new StringBuilder(message);
        Jvm.trimStackTrace(out, thread.getStackTrace());

        if (finalCheck.getAsBoolean() && LOG.isInfoEnabled())
            LOG.info(out.toString());
    }

    public int nonDaemonHandlerCount() {
        return mediumHandlers.size();
    }

    public int handlerCount() {
        return mediumHandlers.size();
    }

    @Override
    public void close() {
        try {
            closed = true;
            closedHere = Jvm.isDebug() ? new StackTrace("Closed here") : null;

            stop();
            pauser.reset(); // reset the timer.
            pauser.unpause();
            LockSupport.unpark(thread);
            Threads.shutdown(service, daemon);
            if (thread == null) {
                loopFinishedAllHandlers();
                return;
            }
            thread.interrupt();

            for (int i = 1; i <= 30; i++) {
                if (loopStartMS == FINISHED)
                    break;
                Jvm.pause(i);

                if (i % 10 == 0) {
                    final StringBuilder sb = new StringBuilder();
                    sb.append(name + ": Shutting down thread is executing ").append(thread)
                            .append(", " + "handlerCount=").append(nonDaemonHandlerCount());
                    Jvm.trimStackTrace(sb, thread.getStackTrace());
                    Jvm.warn().on(getClass(), sb.toString());
                    dumpRunningHandlers();
                }
            }

        } finally {
            closeAllHandlers();
            mediumHandlers.clear();
            mediumHandlersArray = NO_EVENT_HANDLERS;
            newHandler.set(null);
        }
    }

    public void closeAllHandlers() {
        closeAll(mediumHandlers);
        Optional.ofNullable(newHandler.get()).ifPresent(eventHandler -> {
            Jvm.warn().on(getClass(), "Handler in newHandler was not accepted before close " + eventHandler);
            Closeable.closeQuietly(eventHandler);
        });
    }

    public void dumpRunningHandlers() {
        final int handlerCount = handlerCount();
        if (handlerCount <= 0)
            return;
        final List<EventHandler> collect = Stream.of(mediumHandlers)
                .flatMap(List::stream)
                .filter(e -> e instanceof Closeable)
                .collect(Collectors.toList());
        if (collect.isEmpty())
            return;
        LOG.info("Handlers still running after being closed, handlerCount=" + handlerCount);
        collect.forEach(h -> LOG.info("\t" + h));
    }

    @Override
    public boolean isAlive() {
        final Thread thread = this.thread;
        return thread != null && thread.isAlive();
    }
}
