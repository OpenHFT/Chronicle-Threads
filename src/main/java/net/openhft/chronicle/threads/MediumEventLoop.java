/*
 * Copyright 2016-2020 Chronicle Software
 *
 * https://chronicle.software
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
import net.openhft.chronicle.threads.internal.EventLoopUtil;
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

import static net.openhft.chronicle.threads.Threads.loopFinishedQuietly;
import static net.openhft.chronicle.threads.VanillaEventLoop.*;

public class MediumEventLoop implements CoreEventLoop, Runnable, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(MediumEventLoop.class);

    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    private final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    private final AtomicReference<EventHandler> newHandler = new AtomicReference<>();
    @NotNull
    private final AtomicBoolean running = new AtomicBoolean();
    @NotNull
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Pauser pauser;
    private final boolean daemon;
    private final String name;
    private final String binding;

    @NotNull
    private EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    private volatile long loopStartMS;
    @Nullable
    private volatile Thread thread = null;
    @Nullable
    private volatile Throwable closedHere = null;

    /**
     * @param parent  the parent event loop
     * @param name    the name of this event handler
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
        // do not remove the handler here, remove all at end instead
        Closeable.closeQuietly(handlers);
    }

    @Override
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
        return closed.get();
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
            throw new IllegalStateException(hasBeen("closed"), closedHere);
    }

    void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException(hasBeen("interrupted"));
    }

    @Override
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
            Jvm.warn().on(getClass(), hasBeen("terminated due to exception"), e);
        } finally {
            loopFinishedAllHandlers();
            loopStartMS = FINISHED;
        }
    }

    private void loopFinishedAllHandlers() {
        mediumHandlers.forEach(Threads::loopFinishedQuietly);
    }

    private void runLoop() throws InvalidEventHandlerException {
        int acceptHandlerModCount = EventLoopUtil.ACCEPT_HANDLER_MOD_COUNT;
        while (running.get() && isNotInterrupted()) {
            if (isClosed()) {
                throw new InvalidEventHandlerException(hasBeen("closed"));
            }
            boolean busy = runMediumLoopOnly();

            if (busy) {
                pauser.reset();
                /*
                 * This is used for preventing starvation for new event handlers.
                 * Each modulo, potentially new event handlers are added even though
                 * there might be other handlers that are busy.
                 */
                if (EventLoopUtil.IS_ACCEPT_HANDLER_MOD_COUNT && --acceptHandlerModCount <= 0) {
                    acceptNewHandlers();
                    acceptHandlerModCount = EventLoopUtil.ACCEPT_HANDLER_MOD_COUNT; // Re-arm
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
        return !(CHECK_INTERRUPTS && Thread.currentThread().isInterrupted());
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
        final EventHandler[] handlers = this.mediumHandlersArray;
        try {
            switch (handlers.length) {
                case 4:
                    try {
                        busy |= handlers[3].action();
                    } catch (InvalidEventHandlerException e) {
                        removeMediumHandler(handlers[3]);
                    }
                    // fall through
                case 3:
                    try {
                        busy |= handlers[2].action();
                    } catch (InvalidEventHandlerException e) {
                        removeMediumHandler(handlers[2]);
                    }
                    // fall through
                case 2:
                    try {
                        busy |= handlers[1].action();
                    } catch (InvalidEventHandlerException e) {
                        removeMediumHandler(handlers[1]);
                    }
                    // fall through
                case 1:
                    try {
                        busy |= handlers[0].action();
                    } catch (InvalidEventHandlerException e) {
                        removeMediumHandler(handlers[0]);
                    }
                case 0:
                    break;

                default:
                    for (EventHandler handler : handlers) {
                        try {
                            busy |= handler.action();
                        } catch (InvalidEventHandlerException e) {
                            removeMediumHandler(handler);
                        }
                    }
            }

        } catch (Throwable e) {
            Jvm.warn().on(getClass(), e);
        }
        return busy;
    }

    private void removeMediumHandler(EventHandler handler) {
        removeHandler(handler, mediumHandlers);
        this.mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
    }

    private void removeHandler(final EventHandler handler, @NotNull final List<EventHandler> handlers) {
        try {
            handlers.remove(handler);
            loopFinishedQuietly(handler);
        } catch (ArrayIndexOutOfBoundsException e2) {
            if (!handlers.isEmpty())
                throw e2;
        }
        Closeable.closeQuietly(handler);
    }

    @HotMethod
    private boolean acceptNewHandlers() {
        final EventHandler handler = newHandler.getAndSet(null);
        if (handler != null) {
            addNewHandler(handler);
            return true;
        }
        return false;
    }

    private void addNewHandler(@NotNull final EventHandler handler) {
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

    @Override
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
            closed.set(true);
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
            if (thread != Thread.currentThread()) {
                thread.interrupt();

                for (int i = 1; i <= 30; i++) {
                    if (loopStartMS == FINISHED)
                        break;
                    Jvm.pause(i);

                    if (i % 10 == 0) {
                        final StringBuilder sb = new StringBuilder();
                        sb.append(name).append(": Shutting down thread is executing ").append(thread)
                                .append(", " + "handlerCount=").append(nonDaemonHandlerCount());
                        Jvm.trimStackTrace(sb, thread.getStackTrace());
                        Jvm.warn().on(getClass(), sb.toString());
                        dumpRunningHandlers();
                    }
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

    private String hasBeen(String offendingProperty) {
        return String.format("%s has been %s.", MediumEventLoop.class.getSimpleName(), offendingProperty);
    }

}