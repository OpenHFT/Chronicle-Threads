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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.openhft.chronicle.threads.Threads.loopFinishedQuietly;

public class VanillaEventLoop implements CoreEventLoop, Runnable, Closeable {
    public static final Set<HandlerPriority> ALLOWED_PRIORITIES =
            Collections.unmodifiableSet(
                    EnumSet.of(HandlerPriority.HIGH,
                            HandlerPriority.MEDIUM,
                            HandlerPriority.TIMER,
                            HandlerPriority.DAEMON));
    public static final int NO_CPU = -1;
    static final boolean CHECK_INTERRUPTS = !Boolean.getBoolean("chronicle.eventLoop.ignoreInterrupts");
    static final EventHandler[] NO_EVENT_HANDLERS = {};
    static final long FINISHED = Long.MAX_VALUE - 1;
    private static final Logger LOG = LoggerFactory.getLogger(VanillaEventLoop.class);
    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    private final List<EventHandler> highHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> timerHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> daemonHandlers = new CopyOnWriteArrayList<>();
    private final AtomicReference<EventHandler> newHandler = new AtomicReference<>();
    @NotNull
    private final AtomicBoolean running = new AtomicBoolean();
    @NotNull
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Pauser pauser;
    private final long timerIntervalMS;
    private final boolean daemon;
    private final String name;
    private final String binding;
    private final Set<HandlerPriority> priorities;

    @NotNull
    private EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    private volatile long loopStartMS;
    @Nullable
    private volatile Thread thread = null;
    @Nullable
    private volatile Throwable closedHere = null;

    /**
     * @param parent          the parent event loop
     * @param name            the name of this event handler
     * @param pauser          the pause strategy
     * @param timerIntervalMS how long to pause, Long.MAX_VALUE = always check.
     * @param daemon          is a demon thread
     * @param binding         set affinity description, "any", "none", "1", "last-1"
     */
    public VanillaEventLoop(final EventLoop parent,
                            final String name,
                            final Pauser pauser,
                            final long timerIntervalMS,
                            final boolean daemon,
                            final String binding,
                            final Set<HandlerPriority> priorities) {
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.timerIntervalMS = timerIntervalMS;
        this.daemon = daemon;
        this.binding = binding;
        this.priorities = EnumSet.copyOf(priorities);
        loopStartMS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon));
    }

    @Deprecated
    public VanillaEventLoop(final EventLoop parent,
                            final String name,
                            final Pauser pauser,
                            final long timerIntervalMS,
                            final boolean daemon,
                            final boolean binding,
                            final int bindingCpu) {
        this(parent, name, pauser, timerIntervalMS, daemon, bindingCpu != NO_CPU ? Integer.toString(bindingCpu) : binding ? "any" : "none", ALLOWED_PRIORITIES);
    }

    @Deprecated
    public VanillaEventLoop(final EventLoop parent,
                            final String name,
                            final Pauser pauser,
                            final long timerIntervalMS,
                            final boolean daemon,
                            final boolean binding) {
        this(parent, name, pauser, timerIntervalMS, daemon, binding ? "any" : "none", ALLOWED_PRIORITIES);
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
        return "VanillaEventLoop{" +
                "name='" + name + '\'' +
                ", parent=" + parent +
                ", service=" + service +
                ", highHandlers=" + highHandlers +
                ", mediumHandlers=" + mediumHandlers +
                ", timerHandlers=" + timerHandlers +
                ", daemonHandlers=" + daemonHandlers +
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
        if (!priorities.contains(priority))
            throw new IllegalStateException(name() + ": Unexpected priority " + priority + " for " + handler + " allows " + priorities);
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
        highHandlers.forEach(Threads::loopFinishedQuietly);
        mediumHandlers.forEach(Threads::loopFinishedQuietly);
        timerHandlers.forEach(Threads::loopFinishedQuietly);
        daemonHandlers.forEach(Threads::loopFinishedQuietly);
    }

    private void runLoop() throws InvalidEventHandlerException {
        long lastTimerMS = 0;
        int acceptHandlerModCount = EventLoopUtil.ACCEPT_HANDLER_MOD_COUNT;
        while (running.get() && isNotInterrupted()) {
            if (isClosed()) {
                throw new InvalidEventHandlerException();
            }
            boolean busy;
            if (highHandlers.isEmpty()) {
                busy = runMediumLoopOnly();
            } else {
                busy = runHighAndMediumTasks();
            }
            if (lastTimerMS + timerIntervalMS < loopStartMS) {
                lastTimerMS = loopStartMS;
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

    private boolean runHighAndMediumTasks() {
        boolean busy = false;
        for (int i = 0; i < 4; i++) {
            loopStartMS = Time.currentTimeMillis();
            busy |= runAllHighHandlers();
            busy |= runOneQuarterMediumHandler(i);
        }
        return busy;
    }

    private void closeAll() {
        closeAllHandlers();
        LOG.trace("Remaining handlers");
        dumpRunningHandlers();
    }

    @HotMethod
    private boolean runAllHighHandlers() {
        boolean busy = false;
        for (int i = 0; i < highHandlers.size(); i++) {
            final EventHandler handler = highHandlers.get(i);
            try {
                boolean action = handler.action();
                busy |= action;
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, highHandlers);

            } catch (Throwable e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
    }

    @HotMethod
    private boolean runOneQuarterMediumHandler(int i) {
        boolean busy = false;
        final EventHandler[] mediumHandlersArray = this.mediumHandlersArray;
        for (int j = i; j < mediumHandlersArray.length; j += 4) {
            final EventHandler handler = mediumHandlersArray[j];
            try {
                busy |= handler.action();
            } catch (InvalidEventHandlerException e) {
                removeMediumHandler(handler);

            } catch (Throwable e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
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

    @HotMethod
    private void runTimerHandlers() {
        for (int i = 0; i < timerHandlers.size(); i++) {
            EventHandler handler = null;
            try {
                handler = timerHandlers.get(i);
                handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, timerHandlers);

            } catch (Throwable e) {
                Jvm.warn().on(getClass(), e);
            }
        }
    }

    @HotMethod
    private void runDaemonHandlers() {
        for (int i = 0; i < daemonHandlers.size(); i++) {
            EventHandler handler = null;
            try {
                handler = daemonHandlers.get(i);
                handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, daemonHandlers);

            } catch (Throwable e) {
                Jvm.warn().on(getClass(), e);
            }
        }
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
            case HIGH:
                if (!highHandlers.contains(handler))
                    highHandlers.add(handler);
                break;

            case MEDIUM:
                if (!mediumHandlers.contains(handler)) {
                    mediumHandlers.add(handler);
                    mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
                }
                break;

            case TIMER:
                if (!timerHandlers.contains(handler))
                    timerHandlers.add(handler);
                break;

            case DAEMON:
                if (!daemonHandlers.contains(handler))
                    daemonHandlers.add(handler);
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
        return highHandlers.size() + mediumHandlers.size();
    }

    public int handlerCount() {
        return highHandlers.size() + mediumHandlers.size() + daemonHandlers.size() + timerHandlers.size();
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
            highHandlers.clear();
            mediumHandlers.clear();
            mediumHandlersArray = NO_EVENT_HANDLERS;
            daemonHandlers.clear();
            timerHandlers.clear();
            newHandler.set(null);
        }
    }

    public void closeAllHandlers() {
        closeAll(highHandlers);
        closeAll(mediumHandlers);
        closeAll(daemonHandlers);
        closeAll(timerHandlers);
        Optional.ofNullable(newHandler.get()).ifPresent(eventHandler -> {
            Jvm.warn().on(getClass(), "Handler in newHandler was not accepted before close " + eventHandler);
            Closeable.closeQuietly(eventHandler);
        });
    }

    public void dumpRunningHandlers() {
        final int handlerCount = handlerCount();
        if (handlerCount <= 0)
            return;
        final List<EventHandler> collect = Stream.of(highHandlers, mediumHandlers, daemonHandlers, timerHandlers)
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
        return String.format("%s has been %s.", VanillaEventLoop.class.getSimpleName(), offendingProperty);
    }
}