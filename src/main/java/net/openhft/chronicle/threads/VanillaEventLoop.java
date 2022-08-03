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
     * @param parent          the parent event loop
     * @param name            the name of this event handler
     * @param pauser          the pause strategy
     * @param timerIntervalMS how long to pause, Long.MAX_VALUE = always check.
     * @param daemon          is a demon thread
     * @param binding         set affinity description, "any", "none", "1", "last-1"
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

    public static void closeAll(@NotNull final List<EventHandler> handlers) {
        // do not remove the handler here, remove all at end instead
        Closeable.closeQuietly(handlers);
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
                ", newHandler=" + newHandler +
                ", pauser=" + pauser +
                '}';
    }

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        final HandlerPriority priority = handler.priority();
        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + priority + " " + handler + " to " + this.name);
        if (!priorities.contains(priority))
            throw new IllegalStateException(name() + ": Unexpected priority " + priority + " for " + handler + " allows " + priorities);

        if (thread == null || thread == Thread.currentThread()) {
            addNewHandler(handler);
            return;
        }
        do {
            if (isStopped()) {
                return;
            }
            pauser.unpause();

            checkInterruptedAddingNewHandler();
        } while (!newHandler.compareAndSet(null, handler));
    }

    @Override
    protected void loopStartedAllHandlers() {
        super.loopStartedAllHandlers();
        if (!timerHandlers.isEmpty())
            timerHandlers.forEach(EventHandler::loopStarted);
        if (!daemonHandlers.isEmpty())
            daemonHandlers.forEach(EventHandler::loopStarted);
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
        for (int i = 0; i < timerHandlers.size(); i++) {
            EventHandler handler = null;
            try {
                handler = timerHandlers.get(i);
                handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, timerHandlers);
            } catch (Throwable e) {
                if (exceptionThrownByHandler.handle(this, handler, e))
                    removeHandler(handler, timerHandlers);
            }
        }
    }

    @Override
    protected void runDaemonHandlers() {
        for (int i = 0; i < daemonHandlers.size(); i++) {
            EventHandler handler = null;
            try {
                handler = daemonHandlers.get(i);
                handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, daemonHandlers);
            } catch (Throwable e) {
                if (exceptionThrownByHandler.handle(this, handler, e))
                    removeHandler(handler, daemonHandlers);
            }
        }
    }

    @Override
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
                    mediumHandlers.add(handler);
                    mediumHandlers.sort(Comparator.comparing(EventHandler::priority).reversed());
                    updateMediumHandlersArray();
                }
                break;

            case TIMER:
                if (!timerHandlers.contains(handler)) {
                    clearUsedByThread(handler);
                    timerHandlers.add(handler);
                }
                break;

            case DAEMON:
                if (!daemonHandlers.contains(handler)) {
                    clearUsedByThread(handler);
                    daemonHandlers.add(handler);
                }
                break;

            default:
                throw new IllegalArgumentException("Cannot add a " + handler.priority() + " task to a busy waiting thread");
        }
        handler.eventLoop(parent != null ? parent : this);
        if (thread != null)
            handler.loopStarted();
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
        closeAll(daemonHandlers);
        closeAll(timerHandlers);
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