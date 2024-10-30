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
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.openhft.chronicle.threads.Threads.eventLoopQuietly;
import static net.openhft.chronicle.threads.Threads.loopStartedCall;

/**
 * The {@code VanillaEventLoop} class is an event loop implementation that supports
 * multiple handler priorities, including {@code HIGH}, {@code MEDIUM}, {@code TIMER}, and {@code DAEMON}.
 * This class extends {@link MediumEventLoop} and manages separate lists for timer and daemon handlers,
 * allowing for flexible scheduling and management of tasks based on priority.
 */
public class VanillaEventLoop extends MediumEventLoop {
    public static final Set<HandlerPriority> ALLOWED_PRIORITIES =
            Collections.unmodifiableSet(
                    EnumSet.of(HandlerPriority.HIGH,
                            HandlerPriority.MEDIUM,
                            HandlerPriority.TIMER,
                            HandlerPriority.DAEMON));
    private final List<EventHandler> timerHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> daemonHandlers = new CopyOnWriteArrayList<>();
    private final long timerIntervalMS;
    private final Set<HandlerPriority> priorities;

    /**
     * Constructs a new {@code VanillaEventLoop} with the specified configurations.
     *
     * @param parent          the parent event loop, or {@code null} if there is no parent
     * @param name            the name of this event handler
     * @param pauser          the pausing strategy used by this event loop
     * @param timerIntervalMS the interval in milliseconds for timed actions; {@code Long.MAX_VALUE} to always check
     * @param daemon          {@code true} if this event loop runs as a daemon thread, {@code false} otherwise
     * @param binding         a description of the affinity binding, e.g., "any", "none", "1", "last-1"
     * @param priorities      a set of handler priorities that this event loop will accept
     */
    public VanillaEventLoop(@Nullable final EventLoop parent,
                            final String name,
                            final Pauser pauser,
                            final long timerIntervalMS,
                            final boolean daemon,
                            final String binding,
                            final Set<HandlerPriority> priorities) {
        super(parent, name, pauser, daemon, binding);
        this.timerIntervalMS = timerIntervalMS;
        this.priorities = EnumSet.copyOf(priorities);
    }

    /**
     * Closes all event handlers in the specified list.
     *
     * @param handlers the list of handlers to be closed
     */
    public static void closeAll(@NotNull final List<EventHandler> handlers) {
        // do not remove the handler here, remove all at end instead
        Closeable.closeQuietly(handlers);
    }

    /**
     * Clears any thread-specific associations on the given event handler.
     *
     * @param handler the event handler to clear
     */
    private static void clearUsedByThread(@NotNull EventHandler handler) {
        if (handler instanceof AbstractCloseable)
            ((AbstractCloseable) handler).singleThreadedCheckReset();
    }

    /**
     * Returns a string representation of this {@code VanillaEventLoop} including the list of
     * active handlers and associated settings.
     *
     * @return a string description of this event loop
     */
    @NotNull
    @Override
    public String toString() {
        return "VanillaEventLoop{" +
                "name='" + name + '\'' +
                ", parent=" + parent +
                ", service=" + service +
                ", highHandler=" + highHandler +
                ", mediumHandlers=" + mediumHandlers +
                ", timerHandlers=" + timerHandlers +
                ", daemonHandlers=" + daemonHandlers +
                ", newHandlers=" + newHandlers +
                ", pauser=" + pauser +
                '}';
    }

    /**
     * Adds a handler to this event loop. The handler is categorized based on its priority.
     * If the priority of the handler is not supported, an exception is thrown.
     *
     * @param handler the handler to add
     * @throws IllegalStateException if the handler's priority is not allowed in this loop
     */
    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        final HandlerPriority priority = handler.priority();
        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + priority + " " + handler + " to " + this.name);
        if (!priorities.contains(priority))
            throw new IllegalStateException(name() + ": Unexpected priority " + priority + " for " + handler + " allows " + priorities);
        addHandlerInternal(handler);
    }

    /**
     * Initializes all handlers in this loop, including medium-priority, timer, and daemon handlers.
     */
    @Override
    protected void loopStartedAllHandlers() {
        super.loopStartedAllHandlers();
        loopStartedForHandlerList(timerHandlers);
        loopStartedForHandlerList(daemonHandlers);
    }

    /**
     * Cleans up all handlers at the end of the loop, ensuring proper shutdown of timer and daemon handlers.
     */
    @Override
    protected void loopFinishedAllHandlers() {
        super.loopFinishedAllHandlers();
        if (!timerHandlers.isEmpty())
            timerHandlers.forEach(Threads::loopFinishedQuietly);
        if (!daemonHandlers.isEmpty())
            daemonHandlers.forEach(Threads::loopFinishedQuietly);
    }

    @Override
    /**
     * Returns the interval, in milliseconds, at which the event loop should run timer handlers.
     * The value is defined when the `VanillaEventLoop` is initialized.
     *
     * @return the interval in milliseconds for timer executions
     */
    protected long timerIntervalMS() {
        return timerIntervalMS;
    }

    @Override
    /**
     * Iterates over all `TIMER`-priority handlers and executes their `action()` method.
     * If a handler throws an `InvalidEventHandlerException`, it is removed from the list.
     */
    protected void runTimerHandlers() {
        runAllHandlers(timerHandlers);
    }

    @Override
    /**
     * Executes all `DAEMON`-priority handlers in the event loop. If a handler throws an
     * `InvalidEventHandlerException`, it is removed from the list.
     */
    protected void runDaemonHandlers() {
        runAllHandlers(daemonHandlers);
    }

    /**
     * Helper method that iterates over a list of handlers and attempts to execute each
     * handler's `action()` method. Removes any handlers that throw an `InvalidEventHandlerException`
     * or other handled exceptions.
     *
     * @param handlers the list of `EventHandler` instances to execute
     */
    private void runAllHandlers(List<EventHandler> handlers) {
        for (int i = 0; i < handlers.size(); i++) {
            EventHandler handler = null;
            try {
                handler = handlers.get(i);
                handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, handlers);
            } catch (Throwable e) {
                if (handle(this, handler, e))
                    removeHandler(handler, handlers);
            }
        }
    }

    @SuppressWarnings("fallthrough")
    @Override
    /**
     * Adds a new handler to the appropriate list based on its priority level.
     * Supports `HIGH`, `MEDIUM`, `TIMER`, and `DAEMON` priorities, allowing only one
     * `HIGH`-priority handler at a time.
     *
     * @param handler the `EventHandler` instance to add
     * @throws IllegalArgumentException if the handler has an unsupported priority
     */
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

            case MEDIUM:
                if (!mediumHandlers.contains(handler)) {
                    clearUsedByThread(handler);
                    eventLoopQuietly(parent != null ? parent : this, handler);
                    mediumHandlers.add(handler);
                    mediumHandlers.sort(Comparator.comparing(EventHandler::priority).reversed());
                    updateMediumHandlersArray();
                }
                break;

            case TIMER:
                if (!timerHandlers.contains(handler)) {
                    clearUsedByThread(handler);
                    eventLoopQuietly(parent != null ? parent : this, handler);
                    timerHandlers.add(handler);
                }
                break;

            case DAEMON:
                if (!daemonHandlers.contains(handler)) {
                    clearUsedByThread(handler);
                    eventLoopQuietly(parent != null ? parent : this, handler);
                    daemonHandlers.add(handler);
                }
                break;

            default:
                throw new IllegalArgumentException("Cannot add a " + handler.priority() + " task to a busy waiting thread");
        }

        if (thread == Thread.currentThread()) {
            if (loopStartedCall(this, handler)) {
                if (handler == this.highHandler) {
                    removeHighHandler();
                } else {
                    if (mediumHandlers.contains(handler))
                        removeHandler(handler, mediumHandlers);
                    else if (timerHandlers.contains(handler))
                        removeHandler(handler, timerHandlers);
                    else if (daemonHandlers.contains(handler))
                        removeHandler(handler, daemonHandlers);
                }
            }
        }
    }

    @Override
    /**
     * Returns the total number of handlers currently registered with the event loop, including
     * daemon and timer handlers.
     *
     * @return the count of all registered handlers
     */
    public int handlerCount() {
        return nonDaemonHandlerCount() + daemonHandlers.size() + timerHandlers.size();
    }

    @Override
    /**
     * Performs a shutdown of the event loop, clearing daemon and timer handler lists after invoking
     * the superclass's close method.
     */
    protected void performClose() {
        try {
            super.performClose();
        } finally {
            daemonHandlers.clear();
            timerHandlers.clear();
        }
    }

    @Override
    /**
     * Closes all registered daemon and timer handlers in addition to the handlers managed by
     * the superclass.
     */
    protected void closeAllHandlers() {
        closeAll(daemonHandlers);
        closeAll(timerHandlers);
        super.closeAllHandlers();
    }

    @Override
    /**
     * Provides debug output for any handlers that are still running after an attempted close.
     * This method identifies lingering handlers to aid in debugging.
     */
    public void dumpRunningHandlers() {
        final int handlerCount = handlerCount();
        if (handlerCount <= 0)
            return;
        final List<EventHandler> collect = Stream.of(Collections.singletonList(highHandler), mediumHandlers, daemonHandlers, timerHandlers)
                .flatMap(List::stream)
                .filter(e -> e != EventHandlers.NOOP)
                .filter(Closeable.class::isInstance)
                .collect(Collectors.toList());
        if (collect.isEmpty())
            return;
        Jvm.debug().on(getClass(), "Handlers still running after being closed, handlerCount=" + handlerCount);
        collect.forEach(h -> Jvm.debug().on(getClass(), "\t" + h));
    }
}
