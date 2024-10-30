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

/**
 * The {@code MediumEventLoop} is a specialized event loop that manages a single-threaded
 * event handling mechanism with support for high and medium priority handlers. This class
 * is intended for applications that require a balance between responsiveness and efficiency
 * by allowing both active and passive pausing mechanisms.
 *
 * <p>It supports the following handler priorities:
 * <ul>
 *   <li>{@link HandlerPriority#HIGH}</li>
 *   <li>{@link HandlerPriority#MEDIUM}</li>
 * </ul>
 */
public class MediumEventLoop extends AbstractLifecycleEventLoop implements CoreEventLoop, Runnable, Closeable {

    public static final Set<HandlerPriority> ALLOWED_PRIORITIES =
            Collections.unmodifiableSet(
                    EnumSet.of(HandlerPriority.HIGH,
                            HandlerPriority.MEDIUM));
    public static final int NO_CPU = -1;

    protected static final EventHandler[] NO_EVENT_HANDLERS = {};
    private final transient Object addHandlerMutex = new Object();  // Synchronizes handler additions
    private final transient Object startStopMutex = new Object();   // Synchronizes start/stop operations

    @Nullable
    protected transient final EventLoop parent;  // Optional parent event loop
    @NotNull
    protected transient final ExecutorService service;  // Manages the single-threaded execution
    protected final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();  // Handlers with medium priority
    protected final ConcurrentLinkedQueue<EventHandler> newHandlers = new ConcurrentLinkedQueue<>();  // Queue for new handlers
    protected final Pauser pauser;  // Pausing strategy for the event loop
    protected final boolean daemon;  // Indicates if the event loop runs as a daemon thread
    private final String binding;  // CPU affinity binding for the thread

    @NotNull
    protected EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    protected EventHandler highHandler = EventHandlers.NOOP;  // Default high-priority handler

    protected volatile long loopStartNS;
    @Nullable
    protected volatile Thread thread = null;  // Thread executing the event loop

    /**
     * Constructs a new {@code MediumEventLoop}.
     *
     * @param parent  the optional parent event loop
     * @param name    the name of the event loop
     * @param pauser  the pausing strategy to use in the loop
     * @param daemon  whether the event loop should run as a daemon thread
     * @param binding a description of the thread's CPU affinity, e.g., "any", "none", "1", "last-1"
     */
    @SuppressWarnings("this-escape")
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

    /**
     * Closes all {@link EventHandler}s in the specified list quietly.
     *
     * @param handlers the list of handlers to close
     */
    public static void closeAll(@NotNull final List<EventHandler> handlers) {
        // do not remove the handler here, remove all at end instead
        Closeable.closeQuietly(handlers);
    }

    /**
     * Clears any thread-specific state from the provided handler.
     *
     * @param handler the handler to reset
     */
    private static void clearUsedByThread(@NotNull EventHandler handler) {
        if (handler instanceof AbstractCloseable)
            ((AbstractCloseable) handler).singleThreadedCheckReset();
    }

    /**
     * Provides a description of the given property in the context of the {@code MediumEventLoop}.
     *
     * @param offendingProperty the name of the property being described
     * @return a descriptive message about the property
     */
    static String hasBeen(String offendingProperty) {
        return "MediumEventLoop has been " + offendingProperty;
    }

    /**
     * Removes the specified handler from the list of handlers.
     *
     * @param handler  the handler to remove
     * @param handlers the list of handlers from which to remove it
     */
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

    /**
     * Returns the thread currently running the event loop, if any.
     *
     * @return the thread running the event loop, or {@code null} if not running
     */
    @Override
    @Nullable
    public Thread thread() {
        return thread;
    }

    /**
     * Provides a string representation of the {@code MediumEventLoop}.
     *
     * @return a string describing the event loop and its configuration
     */
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

    /**
     * Starts the event loop, submitting it to the executor service and handling rejections.
     */
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

    /**
     * Unpauses the event loop by signaling the {@link Pauser}.
     */
    @Override
    public void unpause() {
        pauser.unpause();
    }

    /**
     * Stops the event loop from the initial state.
     */
    @Override
    protected void performStopFromNew() {
        stopEventLoopThread();
    }

    /**
     * Stops the event loop from the started state.
     */
    @Override
    protected void performStopFromStarted() {
        stopEventLoopThread();
    }

    /**
     * Halts the event loop's execution and shuts down its executor service.
     */
    private void stopEventLoopThread() {
        synchronized (startStopMutex) {
            unpause();
            shutdownService();
        }
    }

    /**
     * Adds a new {@link EventHandler} to the event loop. Ensures that the handler's priority
     * is allowed and throws an exception if the event loop is closed.
     *
     * @param handler the event handler to add
     */
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
     * Adds an {@link EventHandler} in an appropriate manner depending on the current thread and state of the loop.
     *
     * @param handler the handler to add
     */
    protected void addHandlerInternal(@NotNull EventHandler handler) {
        if (thread == null) {
            if (!addHandlerBeforeStart(handler)) {
                addHandlerAfterStart(handler);
            }
        } else if (thread == Thread.currentThread()) {
            // The event loop thread is adding a handler to itself
            addNewHandler(handler);
        } else {
            addHandlerAfterStart(handler);
        }
    }

    /**
     * Adds an event handler before the loop starts, ensuring no other thread interferes.
     *
     * @param handler the handler to add
     * @return {@code true} if the handler was added successfully, {@code false} if the loop already started
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
     * Adds an event handler after the loop has started by queuing it and unpausing the event loop.
     *
     * @param handler the handler to add
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

    /**
     * Returns the start time of the current loop iteration in nanoseconds.
     *
     * @return the loop start time in nanoseconds
     */
    @Override
    public long loopStartNS() {
        return loopStartNS;
    }

    /**
     * Runs the main event loop, setting up affinity locking, managing handler addition, and handling errors.
     */
    @Override
    @HotMethod
    @SuppressWarnings("try")
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

    /**
     * Calls {@code loopStarted} on all handlers and removes any that encounter an exception during the process.
     */
    protected void loopStartedAllHandlers() {
        if (loopStartedCall(this, highHandler)) {
            removeHighHandler();
        }

        loopStartedForHandlerList(mediumHandlers);
        updateMediumHandlersArray();
    }

    /**
     * Calls {@code loopStarted} for each handler in the specified list and removes any that encounter exceptions.
     *
     * @param eventHandlerList the list of handlers to initialize
     */
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

    /**
     * Cleans up all handlers at the end of the loop, logging any handlers that were not processed.
     */
    protected void loopFinishedAllHandlers() {
        loopFinishedQuietly(highHandler);
        if (!mediumHandlers.isEmpty())
            mediumHandlers.forEach(Threads::loopFinishedQuietly);
        newHandlers.forEach(eventHandler -> {
                    Jvm.startup().on(getClass(), "Handler in newHandler was not accepted before loop finished " + eventHandler);
                    loopFinishedQuietly(eventHandler);
                });
    }

    /**
     * Runs the main loop, managing high and medium priority handlers, timers, and new handler acceptance.
     */
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

    /**
     * Returns the interval for timer-based handlers in milliseconds. Defaults to {@code Long.MAX_VALUE / 2}.
     *
     * @return the timer interval in milliseconds
     */
    protected long timerIntervalMS() {
        return Long.MAX_VALUE / 2;
    }

    /**
     * Executes any timer-based handlers. Intended for override by subclasses.
     */
    protected void runTimerHandlers() {
        // Do nothing unless overridden
    }

    /**
     * Executes any daemon handlers. Intended for override by subclasses.
     */
    protected void runDaemonHandlers() {
        // Do nothing unless overridden
    }

    /**
     * Closes all handlers and logs any remaining handlers after closing.
     */
    private void closeAll() {
        closeAllHandlers();
        Jvm.debug().on(getClass(), "Remaining handlers");
        dumpRunningHandlers();
    }

    /**
     * Runs all medium priority handlers sequentially, managing any exceptions and
     * returning a flag indicating if any handler performed work.
     *
     * @return {@code true} if any handler was busy, {@code false} otherwise
     */
    @SuppressWarnings("fallthrough")
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

    /**
     * Runs both high and medium priority handlers, managing exceptions and re-checking the high handler.
     *
     * @return {@code true} if any handler was busy, {@code false} otherwise
     */
    @SuppressWarnings("fallthrough")
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

    /**
     * Invokes the high priority handler and manages exceptions.
     *
     * @return {@code true} if the handler performed work, {@code false} otherwise
     */
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

    /**
     * Removes the high priority handler, performing any necessary cleanup.
     */
    protected void removeHighHandler() {
        Threads.loopFinishedQuietly(highHandler);
        Closeable.closeQuietly(highHandler);
        highHandler = EventHandlers.NOOP;
    }

    /**
     * Handles exceptions from medium priority handlers and removes the handler if necessary.
     *
     * @param handler the handler that caused the exception
     * @param t       the exception to handle
     */
    private void handleExceptionMediumHandler(EventHandler handler, Throwable t) {
        if (handle(this, handler, t)) {
            removeHandler(handler, mediumHandlers);
            updateMediumHandlersArray();
        }
    }

    /**
     * Processes an exception, handling it based on its type.
     *
     * @param eventLoop the event loop handling the exception
     * @param handler   the handler that caused the exception
     * @param t         the exception to process
     * @return {@code true} if the handler should be removed, {@code false} otherwise
     */
    protected boolean handle(EventLoop eventLoop, EventHandler handler, Throwable t) {
        if (!(t instanceof InvalidEventHandlerException)) {
            Jvm.warn().on(eventLoop.getClass(), "Exception thrown by handler " + handler, t);
            return false;
        }
        return true;
    }

    /**
     * Updates the internal array of medium priority handlers. This operation is atomic to ensure
     * consistency when handlers are modified.
     * <p>
     * <a href="https://github.com/OpenHFT/Chronicle-Threads/issues/106">Chronicle-Threads/issues/106</a>
     */
    protected void updateMediumHandlersArray() {
        this.mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
    }

    /**
     * Accepts any new event handlers in the queue, adding them to the handler list.
     *
     * @return {@code true} if new handlers were accepted, {@code false} otherwise
     */
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

    /**
     * Adds a new {@link EventHandler} based on its priority. High priority handlers are set as the
     * single high handler if available; otherwise, they are treated as medium priority handlers.
     *
     * @param handler the handler to add
     */
    @SuppressWarnings("fallthrough")
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
     * Updates the high priority handler atomically if possible.
     *
     * @param handler the high priority handler to set
     * @return {@code true} if the high handler was updated, {@code false} otherwise
     */
    protected boolean updateHighHandler(@NotNull EventHandler handler) {
        if (highHandler == EventHandlers.NOOP || highHandler == handler) {
            eventLoopQuietly(parent != null ? parent : this, handler);
            highHandler = handler;
            return true;
        }
        return false;
    }

    /**
     * Dumps the running state with a stack trace if {@code finalCheck} indicates an issue.
     *
     * @param message    the initial message to log
     * @param finalCheck a supplier to check for final state issues
     */
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

    /**
     * Returns the count of non-daemon handlers in the event loop.
     *
     * @return the count of non-daemon handlers
     */
    public int nonDaemonHandlerCount() {
        return (highHandler == EventHandlers.NOOP ? 0 : 1) +
                mediumHandlers.size();
    }

    /**
     * Returns the total count of handlers, including daemon handlers.
     *
     * @return the total handler count
     */
    public int handlerCount() {
        return nonDaemonHandlerCount();
    }

    /**
     * Closes all handlers, ensuring any remaining new handlers are properly handled.
     */
    protected void closeAllHandlers() {
        Closeable.closeQuietly(highHandler);
        closeAll(mediumHandlers);
        newHandlers.forEach(eventHandler -> {
                    Jvm.startup().on(getClass(), "Handler in newHandler was not accepted before close " + eventHandler);
                    Closeable.closeQuietly(eventHandler);
                });
    }

    /**
     * Logs details of handlers that are still running after the loop has been closed.
     */
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

    /**
     * Checks if the event loop's thread is alive.
     *
     * @return {@code true} if the thread is alive, {@code false} otherwise
     */
    @Override
    public boolean isAlive() {
        final Thread threadSnapshot = this.thread;
        return threadSnapshot != null && threadSnapshot.isAlive();
    }

    /**
     * Closes the event loop, clearing all handlers and resources.
     */
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

    /**
     * Shuts down the executor service and attempts to stop the thread, interrupting it if necessary.
     */
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

    /**
     * Checks if the current thread is running within the core loop.
     *
     * @return {@code true} if inside the core loop, {@code false} otherwise
     */
    @Override
    public boolean runsInsideCoreLoop() {
        return isRunningOnThread(Thread.currentThread()); // false if called before run()
    }

    /**
     * Checks if the specified thread is the event loop's running thread.
     *
     * @param thread the thread to check
     * @return {@code true} if the specified thread is the loop's thread, {@code false} otherwise
     */
    @Override
    public boolean isRunningOnThread(Thread thread) {
        return this.thread == thread;
    }
}
