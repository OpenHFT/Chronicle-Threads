/*
 * Copyright 2016-2025 chronicle.software
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
 * Event loop built on {@link MediumEventLoop}.
 * <p>
 * In addition to the HIGH and MEDIUM priorities handled by the parent class it
 * provides queues for {@link HandlerPriority#TIMER} and
 * {@link HandlerPriority#DAEMON} tasks. TIMER handlers are polled at a fixed
 * interval and DAEMON handlers run when the loop is otherwise idle.
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
     * Creates a VanillaEventLoop.
     *
     * @param parent          optional parent loop, may be {@code null}
     * @param name            name of the worker thread
     * @param pauser          strategy used when the loop is idle
     * @param timerIntervalMS delay between TIMER polls in milliseconds;
     *                        {@code Long.MAX_VALUE} causes a check every cycle
     * @param daemon          whether the worker thread is a daemon
     * @param binding         CPU affinity description such as "any" or "1"
     * @param priorities      set of allowed priorities for {@link #addHandler}
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

    private static void clearUsedByThread(@NotNull EventHandler handler) {
        if (handler instanceof AbstractCloseable)
            ((AbstractCloseable) handler).singleThreadedCheckReset();
    }

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

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        final HandlerPriority priority = handler.priority();
        if (DEBUG_ADDING_HANDLERS)
            Jvm.debug().on(getClass(), "Adding " + priority + " " + handler + " to " + this.name);
        if (!priorities.contains(priority))
            throw new IllegalStateException(name() + ": Unexpected priority " + priority + " for " + handler + " allows " + priorities);
        addHandlerInternal(handler);
    }

    @Override
    protected void loopStartedAllHandlers() {
        super.loopStartedAllHandlers();
        loopStartedForHandlerList(timerHandlers);
        loopStartedForHandlerList(daemonHandlers);
    }

    @Override
    protected void loopFinishedAllHandlers() {
        super.loopFinishedAllHandlers();
        if (!timerHandlers.isEmpty())
            timerHandlers.forEach(Threads::loopFinishedQuietly);
        if (!daemonHandlers.isEmpty())
            daemonHandlers.forEach(Threads::loopFinishedQuietly);
    }

    @Override
    protected long timerIntervalMS() {
        return timerIntervalMS;
    }

    @Override
    protected void runTimerHandlers() {
        runAllHandlers(timerHandlers);
    }

    @Override
    protected void runDaemonHandlers() {
        runAllHandlers(daemonHandlers);
    }

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

    /**
     * Routes new handlers to the appropriate queue. TIMER handlers are placed
     * in {@code timerHandlers} and DAEMON handlers go to {@code daemonHandlers}.
     */
    @Override
    protected void addNewHandler(@NotNull final EventHandler handler) {
        HandlerPriority alias = handler.priority().alias();
        boolean highHandled = false;
        if (alias == HandlerPriority.HIGH) {
            if (updateHighHandler(handler)) {
                highHandled = true;
            } else {
                Jvm.warn().on(getClass(), "Only one high handler supported was " + highHandler + ", treating " + handler + " as MEDIUM");
                alias = HandlerPriority.MEDIUM;
            }
        }

        if (!highHandled && alias == HandlerPriority.MEDIUM) {
            if (!mediumHandlers.contains(handler)) {
                clearUsedByThread(handler);
                eventLoopQuietly(parent != null ? parent : this, handler);
                mediumHandlers.add(handler);
                mediumHandlers.sort(Comparator.comparing(EventHandler::priority).reversed());
                updateMediumHandlersArray();
            }
        } else if (!highHandled && alias == HandlerPriority.TIMER) {
            if (!timerHandlers.contains(handler)) {
                clearUsedByThread(handler);
                eventLoopQuietly(parent != null ? parent : this, handler);
                timerHandlers.add(handler);
            }
        } else if (!highHandled && alias == HandlerPriority.DAEMON) {
            if (!daemonHandlers.contains(handler)) {
                clearUsedByThread(handler);
                eventLoopQuietly(parent != null ? parent : this, handler);
                daemonHandlers.add(handler);
            }
        } else if (!highHandled) {
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
    public int handlerCount() {
        return nonDaemonHandlerCount() + daemonHandlers.size() + timerHandlers.size();
    }

    @Override
    protected void performClose() {
        try {
            super.performClose();
        } finally {
            daemonHandlers.clear();
            timerHandlers.clear();
        }
    }

    @Override
    protected void closeAllHandlers() {
        MediumEventLoop.closeAll(daemonHandlers);
        MediumEventLoop.closeAll(timerHandlers);
        super.closeAllHandlers();
    }

    @Override
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
