/*
 * Copyright 2016-2020 chronicle.software
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
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.threads.internal.EventLoopUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.openhft.chronicle.threads.Threads.loopFinishedQuietly;

public class MediumEventLoop extends AbstractCloseable implements CoreEventLoop, Runnable, Closeable {
    public static final Set<HandlerPriority> ALLOWED_PRIORITIES =
            Collections.unmodifiableSet(
                    EnumSet.of(HandlerPriority.HIGH,
                            HandlerPriority.MEDIUM));
    public static final int NO_CPU = -1;

    protected static final EventHandler[] NO_EVENT_HANDLERS = {};
    protected static final long FINISHED = Long.MAX_VALUE - 1;

    @Nullable
    protected final EventLoop parent;
    @NotNull
    protected final ExecutorService service;
    protected final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    protected final AtomicReference<EventHandler> newHandler = new AtomicReference<>();
    @NotNull
    private final AtomicBoolean running = new AtomicBoolean();
    protected final Pauser pauser;
    protected final boolean daemon;
    protected final String name;
    private final String binding;

    @NotNull
    protected EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    protected EventHandler highHandler = EventHandlers.NOOP;
    @Deprecated(/* to be removed in x.23 */)
    protected volatile long loopStartMS;

    protected volatile long loopStartNS;
    @Nullable
    protected transient volatile Thread thread = null;
    @NotNull
    protected final ExceptionHandlerStrategy exceptionThrownByHandler = ExceptionHandlerStrategy.strategy();

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
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.daemon = daemon;
        this.binding = binding;
        loopStartMS = Long.MAX_VALUE;
        loopStartNS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon, null, true));

        disableThreadSafetyCheck(true);
    }

    public static void closeAll(@NotNull final List<EventHandler> handlers) {
        // do not remove the handler here, remove all at end instead
        Closeable.closeQuietly(handlers);
    }

    private static void clearUsedByThread(@NotNull EventHandler handler) {
        if (handler instanceof AbstractCloseable)
            ((AbstractCloseable) handler).clearUsedByThread();
    }

    static String hasBeen(String offendingProperty) {
        return "MediumEventLoop has been " + offendingProperty;
    }

    protected static void removeHandler(final EventHandler handler, @NotNull final List<EventHandler> handlers) {
        try {
            handlers.remove(handler);
        } catch (ArrayIndexOutOfBoundsException e2) {
            if (!handlers.isEmpty())
                throw e2;
        }
        loopFinishedQuietly(handler);
        Closeable.closeQuietly(handler);
    }

    @Override
    @Nullable
    public Thread thread() {
        return thread;
    }

    @Override
    public void awaitTermination() {
        try {
            service.shutdownNow();
            service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            if (thread != null && thread != Thread.currentThread() && thread.isAlive())
                Jvm.warn().on(getClass(), "Thread still running", StackTrace.forThread(thread));
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
                ", highHandler=" + highHandler +
                ", mediumHandlers=" + mediumHandlers +
                ", newHandler=" + newHandler +
                ", pauser=" + pauser +
                '}';
    }

    @Override
    public void start() {
        throwExceptionIfClosed();

        if (running.getAndSet(true)) {
            return;
        }
        try {
            service.submit(this);
        } catch (IllegalStateException ise) {
            // TODO FIX, don't cause tests for fail.
            Slf4jExceptionHandler.WARN.on(getClass(), "Not started as already closed", ise);

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
        unpause();
    }

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        checkInterrupted();

        final HandlerPriority priority = handler.priority().alias();
        if (DEBUG_ADDING_HANDLERS)
            Jvm.startup().on(getClass(), "Adding " + priority + " " + handler + " to " + this.name);
        if (!ALLOWED_PRIORITIES.contains(priority)) {
            if (handler.priority() == HandlerPriority.MONITOR) {
                Jvm.warn().on(getClass(), "Ignoring " + handler.getClass());
            }
            throw new IllegalStateException(name() + ": Unexpected priority " + priority + " for " + handler);
        }

        if (thread == null || thread == Thread.currentThread()) {
            addNewHandler(handler);
            return;
        }
        do {
            pauser.unpause();
            throwExceptionIfClosed();

            checkInterrupted();
        } while (!newHandler.compareAndSet(null, handler));
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
    public long loopStartNS() {
        return loopStartNS;
    }

    @Override
    @HotMethod
    public void run() {
        try {
            try (AffinityLock lock = AffinityLock.acquireLock(binding)) {
                thread = Thread.currentThread();
                if (thread == null)
                    throw new NullPointerException();
                loopStartedAllHandlers();
                runLoop();
            } catch (IllegalStateException e) {
                // ignore, already closed
            } finally {
                loopFinishedAllHandlers();
                closeAllHandlers();
                loopStartMS = FINISHED;
                loopStartNS = FINISHED;
            }
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), hasBeen("terminated due to exception"), e);
        }
    }

    protected void loopStartedAllHandlers() {
        highHandler.loopStarted();
        if (!mediumHandlers.isEmpty())
            mediumHandlers.forEach(EventHandler::loopStarted);
    }

    protected void loopFinishedAllHandlers() {
        loopFinishedQuietly(highHandler);
        if (!mediumHandlers.isEmpty())
            mediumHandlers.forEach(Threads::loopFinishedQuietly);
        Optional.ofNullable(newHandler.get())
                .ifPresent(eventHandler -> {
                    Jvm.warn().on(getClass(), "Handler in newHandler was not accepted before loop finished " + eventHandler);
                    loopFinishedQuietly(eventHandler);
                });
    }

    private void runLoop() {
        int acceptHandlerModCount = EventLoopUtil.ACCEPT_HANDLER_MOD_COUNT;
        long lastTimerNS = 0;
        while (running.get()) {
            throwExceptionIfClosed();

            loopStartMS = System.currentTimeMillis();
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
                // reset the loop timeout.
                loopStartMS = Long.MAX_VALUE;
                loopStartNS = Long.MAX_VALUE;
                pauser.pause();
            }
        }
    }

    protected long timerIntervalMS() {
        return Long.MAX_VALUE / 2;
    }

    protected void runTimerHandlers() {
    }

    protected void runDaemonHandlers() {
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
                case 1:
                    try {
                        busy |= handlers[0].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[0], e);
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
                case 1:
                    busy |= callHighHandler();
                    try {
                        busy |= handlers[0].action();
                    } catch (Exception e) {
                        handleExceptionMediumHandler(handlers[0], e);
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

    private boolean callHighHandler() throws InterruptedException {
        try {
            return highHandler.action();
        } catch (Exception e) {
            if (exceptionThrownByHandler.handle(this, highHandler, e)) {
                loopFinishedQuietly(highHandler);
                Closeable.closeQuietly(highHandler);
                highHandler = EventHandlers.NOOP;
            }
        }
        return true;
    }

    private void handleExceptionMediumHandler(EventHandler handler, Throwable t) {
        if (exceptionThrownByHandler.handle(this, handler, t)) {
            removeHandler(handler, mediumHandlers);
            this.mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
        }
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

    protected void addNewHandler(@NotNull final EventHandler handler) {
        final HandlerPriority t1 = handler.priority();
        switch (t1.alias()) {
            case HIGH:
                if (highHandler == EventHandlers.NOOP || highHandler == handler) {
                    highHandler = handler;
                    break;
                } else {
                    Jvm.warn().on(getClass(), "Only one high handler supported was " + highHandler + ", treating " + handler + " as MEDIUM");
                    // fall through to MEDIUM
                }

            case REPLICATION:
            case CONCURRENT:
            case DAEMON:
            case MEDIUM:
                if (!mediumHandlers.contains(handler)) {
                    clearUsedByThread(handler);
                    mediumHandlers.add(handler);
                    mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);
                    handler.eventLoop(parent != null ? parent : this);
                    handler.loopStarted();
                }
                break;

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
        handler.eventLoop(parent != null ? parent : this);
        if (thread != null)
            handler.loopStarted();
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

        if (finalCheck.getAsBoolean() && Jvm.isDebugEnabled(getClass()))
            Jvm.debug().on(getClass(), out.toString());
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
        Optional.ofNullable(newHandler.get())
                .ifPresent(eventHandler -> {
                    Jvm.warn().on(getClass(), "Handler in newHandler was not accepted before close " + eventHandler);
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
                .filter(e -> e instanceof Closeable)
                .collect(Collectors.toList());
        if (collect.isEmpty())
            return;
        Jvm.debug().on(getClass(), "Handlers still running after being closed, handlerCount=" + handlerCount);
        collect.forEach(h -> Jvm.debug().on(getClass(), "\t" + h));
    }

    @Override
    public boolean isAlive() {
        final Thread thread = this.thread;
        return thread != null && thread.isAlive();
    }

    @Override
    protected void performClose() {
        try {
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
                long startTimeMillis = System.currentTimeMillis();
                long waitUntilMs = startTimeMillis;
                thread.interrupt();

                for (int i = 1; i <= 50; i++) {
                    if (loopStartNS == FINISHED)
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
        } finally {
            closeAllHandlers();
            highHandler = EventHandlers.NOOP;
            mediumHandlers.clear();
            mediumHandlersArray = NO_EVENT_HANDLERS;
            newHandler.set(null);
        }
    }
}