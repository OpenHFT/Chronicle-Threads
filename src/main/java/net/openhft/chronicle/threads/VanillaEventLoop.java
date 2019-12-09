/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

/*
 * Created by peter.lawrey on 22/01/15.
 */
public class VanillaEventLoop implements EventLoop, Runnable, Closeable {
    public static final int NO_CPU = -1;
    private static final boolean CHECK_INTERRUPTS = !Boolean.getBoolean("chronicle.eventLoop" +
            ".ignoreInterrupts");
    private static final Logger LOG = LoggerFactory.getLogger(VanillaEventLoop.class);
    private static final EventHandler[] NO_EVENT_HANDLERS = {};
    private static final long FINISHED = Long.MAX_VALUE - 1;
    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    private final List<EventHandler> highHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> timerHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> daemonHandlers = new CopyOnWriteArrayList<>();
    private final AtomicReference<EventHandler> newHandler = new AtomicReference<>();
    private final Pauser pauser;
    private final long timerIntervalMS;
    private final boolean daemon;
    private final String name;
    private final String binding;
    @NotNull
    private EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    private long lastTimerMS;
    private volatile long loopStartMS;
    @NotNull
    private volatile AtomicBoolean running = new AtomicBoolean();
    @Nullable
    private volatile Thread thread = null;
    @Nullable
    private volatile Throwable closedHere = null;
    private volatile boolean closed;

    /**
     * @param parent          the parent event loop
     * @param name            the name of this event hander
     * @param pauser          the pause strategy
     * @param timerIntervalMS how long to pause, Long.MAX_VALUE = always check.
     * @param daemon          is a demon thread
     * @param binding         set affinity description, "any", "none", "1", "last-1"
     */
    public VanillaEventLoop(EventLoop parent,
                            String name,
                            Pauser pauser,
                            long timerIntervalMS,
                            boolean daemon,
                            String binding) {
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.timerIntervalMS = timerIntervalMS;
        this.daemon = daemon;
        this.binding = binding;
        loopStartMS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon));
    }

    @Deprecated
    public VanillaEventLoop(EventLoop parent,
                            String name,
                            Pauser pauser,
                            long timerIntervalMS,
                            boolean daemon,
                            boolean binding,
                            int bindingCpu) {
        this(parent, name, pauser, timerIntervalMS, daemon, bindingCpu != NO_CPU ? Integer.toString(bindingCpu) : binding ? "any" : "none");
    }

    @Deprecated
    public VanillaEventLoop(EventLoop parent,
                            String name,
                            Pauser pauser,
                            long timerIntervalMS,
                            boolean daemon,
                            boolean binding) {
        this(parent, name, pauser, timerIntervalMS, daemon, binding ? "any" : "none");
    }

    public static void closeAll(@NotNull List<EventHandler> handlers) {
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
        return closed;
    }

    @Override
    public void addHandler(@NotNull EventHandler handler) {
        checkClosed();

        if (thread == null || thread == Thread.currentThread()) {
            addNewHandler(handler);
            return;
        }
        do {
            pauser.unpause();
            checkClosed();
        } while (!newHandler.compareAndSet(null, handler));
    }

    void checkClosed() {
        if (isClosed())
            throw new IllegalStateException("Event Group has been closed", closedHere);
    }

    public long loopStartMS() {
        return loopStartMS;
    }

    @Override
    @HotMethod
    public void run() {
        try (AffinityLock affinityLock = AffinityLock.acquireLock(binding)) {
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
        highHandlers.forEach(EventHandler::loopFinished);
        mediumHandlers.forEach(EventHandler::loopFinished);
        timerHandlers.forEach(EventHandler::loopFinished);
        daemonHandlers.forEach(EventHandler::loopFinished);
    }

    private void runLoop() throws InvalidEventHandlerException {
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
        return !CHECK_INTERRUPTS || !Thread.currentThread().isInterrupted();
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
            EventHandler handler = highHandlers.get(i);
            try {
                boolean action = handler.action();
                busy |= action;
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, highHandlers);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
    }

    @HotMethod
    private boolean runOneQuarterMediumHandler(int i) {
        boolean busy = false;
        EventHandler[] mediumHandlersArray = this.mediumHandlersArray;
        for (int j = i; j < mediumHandlersArray.length; j += 4) {
            EventHandler handler = mediumHandlersArray[j];
            try {
                busy |= handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, mediumHandlers);
                this.mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);

            } catch (Throwable e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
    }

    @HotMethod
    private boolean runAllMediumHandler() {
        boolean busy = false;
        EventHandler[] mediumHandlersArray = this.mediumHandlersArray;
        for (EventHandler handler : mediumHandlersArray) {
            try {
                busy |= handler.action();
            } catch (InvalidEventHandlerException e) {
                removeHandler(handler, mediumHandlers);
                this.mediumHandlersArray = mediumHandlers.toArray(NO_EVENT_HANDLERS);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
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

            } catch (Exception e) {
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

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
    }

    private void removeHandler(EventHandler handler, @NotNull List<EventHandler> handlers) {
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
        EventHandler handler = newHandler.getAndSet(null);
        if (handler != null) {
            addNewHandler(handler);
            busy = true;
        }
        return busy;
    }

    private void addNewHandler(@NotNull EventHandler handler) {
        HandlerPriority t1 = handler.priority();
        switch (t1 == null ? HandlerPriority.MEDIUM : t1) {
            case HIGH:
                if (!highHandlers.contains(handler))
                    highHandlers.add(handler);
                break;

            case REPLICATION:
            case CONCURRENT:
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

    public void dumpRunningState(@NotNull String message, @NotNull BooleanSupplier finalCheck) {
        Thread thread = this.thread;
        if (thread == null) return;
        StringBuilder out = new StringBuilder(message);
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
                    StringBuilder sb = new StringBuilder();
                    sb.append(name + ": Shutting down thread is executing ").append(thread)
                            .append(", " + "handlerCount=").append(nonDaemonHandlerCount());
                    Jvm.trimStackTrace(sb, thread.getStackTrace());
                    Jvm.warn().on(getClass(), sb.toString());
                    dumpRunningHandlers();
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
        List<EventHandler> collect = Stream.of(highHandlers, mediumHandlers, daemonHandlers, timerHandlers)
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
        Thread thread = this.thread;
        return thread != null && thread.isAlive();
    }
}
