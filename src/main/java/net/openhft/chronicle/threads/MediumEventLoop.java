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

import net.openhft.affinity.AffinityLock;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.HotMethod;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.threads.internal.EventLoopUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.openhft.chronicle.threads.Threads.*;

public class MediumEventLoop extends AbstractLifecycleEventLoop implements CoreEventLoop, Runnable, Closeable {
    public static final Set<HandlerPriority> ALLOWED_PRIORITIES =
            Collections.unmodifiableSet(
                    EnumSet.of(HandlerPriority.HIGH,
                            HandlerPriority.MEDIUM));
    public static final int NO_CPU = -1;

    protected static final EventHandler[] NO_EVENT_HANDLERS = {};
    /**
     * This ensures only a single non-event-loop thread can add a handler at a time
     */
    private final transient Object addHandlerMutex = new Object();
    private final transient Object startStopMutex = new Object();

    @Nullable
    protected transient final EventLoop parent;
    @NotNull
    protected transient final ExecutorService service;
    protected final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    protected final ConcurrentLinkedQueue<EventHandler> newHandlers = new ConcurrentLinkedQueue<>();
    protected final Pauser pauser;
    protected final boolean daemon;
    private final String binding;

    @NotNull
    protected EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    protected EventHandler highHandler = EventHandlers.NOOP;

    protected volatile long loopStartNS;
    @Nullable
    protected volatile Thread thread = null;

    /**
     * @param parent  the parent event loop
     * @param name    the name of this event handler
     * @param pauser  the pause strategy
     * @param daemon  is a demon thread
     * @param binding set affinity description, "any", "none", "1", "last-1"
     */
    public MediumEventLoop(@Nullable final EventLoop parent,
                           final String name,
                           final Pauser pauser,
                           final boolean daemon,
                           final String binding) {
        super(name);
        this.parent = parent;
        this.pauser = pauser;
        this.daemon = daemon;
        this.binding = binding;
        loopStartNS = NOT_IN_A_LOOP;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon, null, true));

        singleThreadedCheckDisabled(true);
    }

    public static void closeAll(@NotNull final List<EventHandler> handlers) {
        // do not remove the handler here, remove all at end instead
        Closeable.closeQuietly(handlers);
    }

    private static void clearUsedByThread(@NotNull EventHandler handler) {
        if (handler instanceof AbstractCloseable)
            ((AbstractCloseable) handler).singleThreadedCheckReset();
    }

    static String hasBeen(String offendingProperty) {
        return "MediumEventLoop has been " + offendingProperty;
    }

    protected static void removeHandler(final EventHandler handler, @NotNull final List<EventHandler> handlers) {
        // Close the handler before removing it from the list
        loopFinishedQuietly(handler);
        Closeable.closeQuietly(handler);
        try {
            handlers.remove(handler);
        } catch (ArrayIndexOutOfBoundsException e2) {
            if (!handlers.isEmpty())
                throw e2;
        }
    }

    @Override
    @Nullable
    public Thread thread() {
        return thread;
    }

    @NotNull
    @Override
    public String toString() {
        return "MediumEventLoop{" +
                "name='" + name + '\'' +
                ", parent=" + parent +
                ", service=" + service +
                ", highHandler=" + highHandler +
                ", mediumHandlers=" + mediumHandlers +
                ", newHandlers=" + newHandlers +
                ", pauser=" + pauser +
                '}';
    }

    @Override
    protected void performStart() {
        synchronized (startStopMutex) {
            try {
                service.submit(this);
            } catch (RejectedExecutionException e) {
                if (!isStopped()) {
                    closeAll();
                    throw e;
                }
            }
        }
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    @Override
    protected void performStopFromNew() {
        stopEventLoopThread();
    }

    @Override
    protected void performStopFromStarted() {
        stopEventLoopThread();
    }

    private void stopEventLoopThread() {
        synchronized (startStopMutex) {
            unpause();
            shutdownService();
        }
    }

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        final HandlerPriority priority = handler.priority().alias();
        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + priority + " " + handler + " to " + this.name);
        if (!ALLOWED_PRIORITIES.contains(priority)) {
            if (handler.priority() == HandlerPriority.MONITOR) {
                Jvm.warn().on(getClass(), "Ignoring " + handler.getClass());
            }
            throw new IllegalStateException(name() + ": Unexpected priority " + priority + " for " + handler);
        }
        addHandlerInternal(handler);
    }

    /**
     * Add a handler in the appropriate way given the thread adding the handler and the state of the loop
     */
    protected void addHandlerInternal(@NotNull EventHandler handler) {
        if (thread == null) {
            if (!addHandlerBeforeStart(handler)) {
                addHandlerAfterStart(handler);
            }
        } else if (thread == Thread.currentThread()) {
            // The event loop thread adding a handler to itself
            addNewHandler(handler);
        } else {
            addHandlerAfterStart(handler);
        }
    }

    /**
     * This is the code used when any thread tries to add an event handler before a loop is started
     */
    private boolean addHandlerBeforeStart(@NotNull EventHandler handler) {
        synchronized (addHandlerMutex) {
            if (thread != null) {
                // The loop started since the initial check, fall back to after-start behaviour
                return false;
            }
            addNewHandler(handler);
        }
        return true;
    }

    /**
     * This is the code executed when a non-event-loop thread wants to add a handler on a started loop
     */
    private void addHandlerAfterStart(@NotNull EventHandler handler) {
        if (isStopped()) {
            if (Jvm.isDebugEnabled(MediumEventLoop.class)) {
                Jvm.debug().on(MediumEventLoop.class, "Aborted adding handler because event loop was stopped, handler=" + handler);
            }
            return;
        }

        newHandlers.offer(handler);

        pauser.unpause();
    }

    @Override
    public long loopStartNS() {
        return loopStartNS;
    }

    @Override
    @HotMethod
    public void run() {
        try {
            try (AffinityLock lock = AffinityLock.acquireLock(binding)) {
                // Make sure nobody's adding a handler while we do this
                synchronized (addHandlerMutex) {
                    thread = Thread.currentThread();
                    if (thread == null)
                        throw new NullPointerException();
                    loopStartedAllHandlers();
                }
                runLoop();
            } catch (ClosedIllegalStateException e) {
                if (!isClosing()) {
                    // Event loop isn't closed
                    Jvm.rethrow(e);
                }
                // otherwise ignore, already closed
            } finally {
                loopFinishedAllHandlers();
                loopStartNS = NOT_IN_A_LOOP;
            }
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), hasBeen("terminated due to exception"), e);
            stop();
        }
    }

    protected void loopStartedAllHandlers() {
        if (loopStartedCall(this, highHandler)) {
            removeHighHandler();
        }

        loopStartedForHandlerList(mediumHandlers);
        updateMediumHandlersArray();
    }

    protected void loopStartedForHandlerList(@NotNull List<EventHandler> eventHandlerList) {
        List<EventHandler> removeHandlers = new ArrayList<>();
        for (EventHandler handler : eventHandlerList) {
            if (loopStartedCall(this, handler)) {
                // iterator.remove() is not supported.
                removeHandlers.add(handler);
            }
        }

        // Remove handlers that had exception in loopStarted.
        for (EventHandler handler : removeHandlers) {
            removeHandler(handler, eventHandlerList);
        }
    }

    protected void loopFinishedAllHandlers() {
        loopFinishedQuietly(highHandler);
        if (!mediumHandlers.isEmpty())
            mediumHandlers.forEach(Threads::loopFinishedQuietly);
        newHandlers.forEach(eventHandler -> {
                    Jvm.startup().on(getClass(), "Handler in newHandler was not accepted before loop finished " + eventHandler);
                    loopFinishedQuietly(eventHandler);
                });
    }

    private void runLoop() {
        int acceptHandlerModCount = EventLoopUtil.ACCEPT_HANDLER_MOD_COUNT;
        long lastTimerNS = 0;
        while (isStarted()) {
            throwExceptionIfClosed();

            loopStartNS = System.nanoTime();
            boolean busy =
                    highHandler == EventHandlers.NOOP
                            ? runAllMediumHandler()
                            : runAllHandlers();

            if (lastTimerNS + timerIntervalMS() * 1_000_000 < loopStartNS) {
                lastTimerNS = loopStartNS;
                runTimerHandlers();
            }
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

                runDaemonHandlers();
                // indicate the iteration is complete
                loopStartNS = NOT_IN_A_LOOP;
                pauser.pause();
            }
        }
    }

    protected long timerIntervalMS() {
        return Long.MAX_VALUE / 2;
    }

    protected void runTimerHandlers() {
        // Do nothing unless overridden
    }

    protected void runDaemonHandlers() {
        // Do nothing unless overridden
    }

    private void closeAll() {
        closeAllHandlers();
        Jvm.debug().on(getClass(), "Remaining handlers");
        dumpRunningHandlers();
    }

    private boolean runAllMediumHandler() {
        boolean busy = false;
        final EventHandler[] handlers = this.mediumHandlersArray;
        try {
            switch (handlers.length) {
                default:
                    for (int i = handlers.length - 1; i >= 4; i--) {
                        try {
                            busy |= handlers[i].action();
                        } catch (Exception e) {
                            handleExceptionMediumHandler(handlers[i], e);
                        }
                    }
                    // fallthrough.

                case 4:
                    try {
                        busy |= handlers[3].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[3], e);
                    }
                    // fall through
                case 3:
                    try {
                        busy |= handlers[2].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[2], e);
                    }
                    // fall through
                case 2:
                    try {
                        busy |= handlers[1].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[1], e);
                    }
                    // fall through
                case 1: {
                    try {
                        busy |= handlers[0].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[0], e);
                    }
                    break;
                }
                case 0:
                    break;

            }
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), e);
        }
        return busy;
    }

    // NOTE The loop is unrolled to reduce megamorphic calls.
    protected boolean runAllHandlers() {
        boolean busy = false;
        final EventHandler[] handlers = this.mediumHandlersArray;
        try {
            // run HIGH handler
            busy |= callHighHandler();

            switch (handlers.length) {
                default:
                    for (int i = handlers.length - 1; i >= 4; i--) {
                        busy |= callHighHandler();
                        try {
                            busy |= handlers[i].action();
                        } catch (Exception e) {
                            handleExceptionMediumHandler(handlers[i], e);
                        }
                    }
                    // fallthrough.

                case 4:
                    busy |= callHighHandler();
                    try {
                        busy |= handlers[3].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[3], e);
                    }
                    // fall through
                case 3:
                    busy |= callHighHandler();
                    try {
                        busy |= handlers[2].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[2], e);
                    }
                    // fall through
                case 2:
                    busy |= callHighHandler();
                    try {
                        busy |= handlers[1].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[1], e);
                    }
                    // fall through
                case 1: {
                    busy |= callHighHandler();
                    try {
                        busy |= handlers[0].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[0], e);
                    }
                    break;
                }
                case 0:
                    break;

            }

            // run HIGH handler again
            busy |= callHighHandler();
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), e);
        }
        return busy;
    }

    private boolean callHighHandler() {
        try {
            return highHandler.action();
        } catch (Exception e) {
            if (handle(this, highHandler, e)) {
                removeHighHandler();
            }
        }
        return true;
    }

   protected void removeHighHandler() {
        Threads.loopFinishedQuietly(highHandler);
        Closeable.closeQuietly(highHandler);
        highHandler = EventHandlers.NOOP;
    }

    private void handleExceptionMediumHandler(EventHandler handler, Throwable t) {
        if (handle(this, handler, t)) {
            removeHandler(handler, mediumHandlers);
            updateMediumHandlersArray();
        }
    }

    protected boolean handle(EventLoop eventLoop, EventHandler handler, Throwable t) {
        if (!(t instanceof InvalidEventHandlerException)) {
            Jvm.warn().on(eventLoop.getClass(), "Exception thrown by handler " + handler, t);
            return false;
        }
        return true;
    }

    /**
     * This copy needs to be atomic
     * <p>
     * <a href="https://github.com/OpenHFT/Chronicle-Threads/issues/106">Chronicle-Threads/issues/106</a>
     */
    protected void updateMediumHandlersArray() {
        this.mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
    }

    @HotMethod
    private boolean acceptNewHandlers() {
        boolean result = false;
        EventHandler handler;
        while ((handler = newHandlers.poll()) != null) {
            addNewHandler(handler);
            result = true;
        }
        return result;
    }

    protected void addNewHandler(@NotNull final EventHandler handler) {
        final HandlerPriority t1 = handler.priority();
        switch (t1.alias()) {
            case HIGH:
                if (updateHighHandler(handler)) {
                    break;
                } else {
                    Jvm.warn().on(getClass(), "Only one high handler supported was " + highHandler + ", treating " + handler + " as MEDIUM");
                    // fall through to MEDIUM
                }

            case REPLICATION:
            case CONCURRENT:
            case DAEMON:
            case MEDIUM: {
                if (!mediumHandlers.contains(handler)) {
                    clearUsedByThread(handler);
                    handler.eventLoop(parent != null ? parent : this);
                    mediumHandlers.add(handler);
                    updateMediumHandlersArray();
                }
                break;
            }

            case MONITOR:
                if (parent != null) {
                    Jvm.warn().on(getClass(), "Handler " + handler.getClass() + " ignored");
                    return;
                }

            case BLOCKING:
            case TIMER:
            default:
                throw new IllegalArgumentException("Cannot add a " + handler.priority() + " task to a busy waiting thread");
        }

        if (thread == Thread.currentThread()) {
            if (loopStartedCall(this, handler)) {
                if (handler == this.highHandler) {
                    removeHighHandler();
                } else {
                    removeHandler(handler, mediumHandlers);
                    updateMediumHandlersArray();
                }
            }
        }
    }

    /**
     * This check/assignment needs to be atomic
     */
    protected boolean updateHighHandler(@NotNull EventHandler handler) {
        if (highHandler == EventHandlers.NOOP || highHandler == handler) {
            eventLoopQuietly(parent != null ? parent : this, handler);
            highHandler = handler;
            return true;
        }
        return false;
    }

    @Override
    public void dumpRunningState(@NotNull final String message, @NotNull final BooleanSupplier finalCheck) {
        final Thread threadSnapshot = this.thread;
        if (threadSnapshot == null || !Jvm.isPerfEnabled(getClass()))
            return;
        final StringBuilder out = new StringBuilder(message);
        final int messageIndex = out.length();
        final long startTimeNanos = System.nanoTime();
        Jvm.trimStackTrace(out, threadSnapshot.getStackTrace());

        if (!finalCheck.getAsBoolean()) {
            // Previously, we did not log anything when finalCheck failed, leading to surprises when loop block monitor
            // detected pauses but a slow getStackTrace() meant the warning was not logged.
            // Better to log that a blockage was found (and that the user has paid for a slow getStackTrace())
            final long timeToTakeStackTraceMillis = (System.nanoTime() - startTimeNanos) / 1_000_000;
            out.setLength(messageIndex);
            out.append(" An accurate stack trace could not be determined (capturing the stack trace took " + timeToTakeStackTraceMillis + "ms)");
        }
        Jvm.perf().on(getClass(), out.toString());
    }

    public int nonDaemonHandlerCount() {
        return (highHandler == EventHandlers.NOOP ? 0 : 1) +
                mediumHandlers.size();
    }

    public int handlerCount() {
        return nonDaemonHandlerCount();
    }

    protected void closeAllHandlers() {
        Closeable.closeQuietly(highHandler);
        closeAll(mediumHandlers);
        newHandlers.forEach(eventHandler -> {
                    Jvm.startup().on(getClass(), "Handler in newHandler was not accepted before close " + eventHandler);
                    Closeable.closeQuietly(eventHandler);
                });
    }

    public void dumpRunningHandlers() {
        final int handlerCount = handlerCount();
        if (handlerCount <= 0)
            return;
        final List<EventHandler> collect = Stream.of(Collections.singletonList(highHandler), mediumHandlers)
                .flatMap(List::stream)
                .filter(e -> e != EventHandlers.NOOP)
                .filter(Closeable.class::isInstance)
                .collect(Collectors.toList());
        if (collect.isEmpty())
            return;
        Jvm.debug().on(getClass(), "Handlers still running after being closed, handlerCount=" + handlerCount);
        collect.forEach(h -> Jvm.debug().on(getClass(), "\t" + h));
    }

    @Override
    public boolean isAlive() {
        final Thread threadSnapshot = this.thread;
        return threadSnapshot != null && threadSnapshot.isAlive();
    }

    @Override
    protected void performClose() {
        try {
            super.performClose();
        } finally {
            closeAllHandlers();
            highHandler = EventHandlers.NOOP;
            mediumHandlers.clear();
            updateMediumHandlersArray();
            newHandlers.clear();
        }
    }

    private void shutdownService() {
        LockSupport.unpark(thread);
        Threads.shutdown(service, daemon);
        if (thread != null && thread != Thread.currentThread()) {
            long startTimeMillis = System.currentTimeMillis();
            long waitUntilMs = startTimeMillis;
            thread.interrupt();

            for (int i = 1; i <= 50; i++) {
                if (!thread.isAlive())
                    break;
                // we do this loop below to protect from Jvm.pause not pausing for as long as it should
                waitUntilMs += i;
                while (System.currentTimeMillis() < waitUntilMs)
                    Jvm.pause(i);

                if (i == 35 || i == 50) {
                    final StringBuilder sb = new StringBuilder();
                    long ms = System.currentTimeMillis() - startTimeMillis;
                    sb.append(name).append(": Shutting down thread is executing after ").
                            append(ms).append("ms ").append(thread)
                            .append(", " + "handlerCount=").append(nonDaemonHandlerCount());
                    Jvm.trimStackTrace(sb, thread.getStackTrace());
                    Jvm.warn().on(getClass(), sb.toString());
                    dumpRunningHandlers();
                }
            }
        }
    }

    @Override
    public boolean runsInsideCoreLoop() {
        return thread == Thread.currentThread(); // false if called before run()
    }
}