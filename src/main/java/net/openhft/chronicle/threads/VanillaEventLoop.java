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
import java.util.Queue;
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
    private static final Logger LOG = LoggerFactory.getLogger(VanillaEventLoop.class);
    private static final EventHandler[] NO_EVENT_HANDLERS = {};
    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    private final List<EventHandler> highHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> timerHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> daemonHandlers = new CopyOnWriteArrayList<>();
    private final AtomicReference<EventHandler> newHandler = new AtomicReference<>();
    private final Queue<EventHandler> newHandlerQueue = new LinkedTransferQueue<>();
    private final Pauser pauser;
    private final long timerIntervalMS;
    private final boolean daemon;
    private final String name;
    private final boolean binding;
    private final int bindingCpu;
    @NotNull
    private EventHandler[] mediumHandlersArray = NO_EVENT_HANDLERS;
    private long lastTimerNS;
    private volatile long loopStartMS;
    @NotNull
    private volatile AtomicBoolean running = new AtomicBoolean();
    @Nullable
    private volatile Thread thread = null;
    @Nullable
    private volatile Throwable closedHere = null;

    /**
     * @param parent          the parent event loop
     * @param name            the name of this event hander
     * @param pauser          the pause strategy
     * @param timerIntervalMS how long to pause
     * @param daemon          is a demon thread
     * @param binding         set affinity
     * @param bindingCpu      cpu to bind to
     */
    public VanillaEventLoop(EventLoop parent,
                            String name,
                            Pauser pauser,
                            long timerIntervalMS,
                            boolean daemon,
                            boolean binding,
                            int bindingCpu) {
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.timerIntervalMS = timerIntervalMS;
        this.daemon = daemon;
        this.binding = binding;
        this.bindingCpu = bindingCpu;
        loopStartMS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon));
    }

    public VanillaEventLoop(EventLoop parent,
                            String name,
                            Pauser pauser,
                            long timerIntervalMS,
                            boolean daemon,
                            boolean binding) {
        this(parent, name, pauser, timerIntervalMS, daemon, binding, NO_CPU);
    }

    public static void closeAll(@NotNull List<EventHandler> handlers) {
        handlers.forEach(h -> {
            if (h instanceof Closeable) {
                Closeable.closeQuietly(h);
            } else {
                handlers.remove(h);
            }
        });
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
                ", newHandlerQueue=" + newHandlerQueue +
                ", pauser=" + pauser +
                ", closedHere=" + closedHere +
                '}';
    }

    @Override
    public void start() {
        if (closedHere != null)
            throw new IllegalStateException("Event Group has been closed", closedHere);
        if (!running.getAndSet(true))
            try {
                service.submit(this);
            } catch (RejectedExecutionException e) {
                if (!isClosed())
                    throw e;
            }
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    @Override
    public void stop() {

    }

    @Override
    public boolean isClosed() {
        return closedHere != null;
    }

    @Override
    public void addHandler(@NotNull EventHandler handler) {
        addHandler(false, handler);
    }

    @Override
    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        if (thread == null || thread == Thread.currentThread()) {
            addNewHandler(handler);

        } else {

            if (!running.get()) {
                if (!dontAttemptToRunImmediatelyInCurrentThread) {
                    try {
                        if (LOG.isDebugEnabled())
                            Jvm.debug().on(getClass(), "Running " + handler + " in the current thread as " + this + " has finished");
                        handler.action();
                    } catch (InterruptedException e) {
                        Jvm.warn().on(getClass(), e);

                    } catch (InvalidEventHandlerException ignored) {
                    }
                }
                return;
            }
            pauser.unpause();
            if (!newHandler.compareAndSet(null, handler))
                newHandlerQueue.add(handler);

        }
    }

    public long loopStartMS() {
        return loopStartMS;
    }

    @Override
    @HotMethod
    public void run() {
        AffinityLock affinityLock = null;
        try {
            if (bindingCpu != NO_CPU)
                affinityLock = AffinityLock.acquireLock(bindingCpu);
            else if (binding)
                affinityLock = AffinityLock.acquireLock();

            thread = Thread.currentThread();
            runLoop();
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), "Loop terminated due to exception", e);

        } finally {
            loopStartMS = Long.MAX_VALUE - 1;
            if (affinityLock != null)
                affinityLock.release();
        }
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (closedHere != null) {
                closeAll();
                break;
            }
            boolean busy;
            if (highHandlers.isEmpty()) {
                busy = runMediumLoopOnly();

            } else {
                busy = runHighAndMediumTasks();
            }
            if (lastTimerNS + timerIntervalMS < loopStartMS) {
                lastTimerNS = loopStartMS;
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
        LOG.trace("Closing down handlers");
        closeAllHandlers();
        runAllHighHandlers();
        runAllMediumHandler();
        runDaemonHandlers();
        runTimerHandlers();
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
        while ((handler = newHandlerQueue.poll()) != null) {
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

    public int handlerCount() {
        return highHandlers.size() + mediumHandlers.size() + daemonHandlers.size() + timerHandlers.size();
    }

    @Override
    public void close() {
        try {
            closedHere = Jvm.isDebug() ? new Throwable("Closed here") : null;

            pauser.reset(); // reset the timer.
            closeAllHandlers();

            if (thread == null) {
                Threads.shutdown(service, daemon);
                return;
            }

            for (int i = 1; i <= 30; i++) {
                pauser.unpause();

                Jvm.pause(i);
                if (handlerCount() == 0)
                    break;
                if (i % 10 == 4) {
                    LockSupport.unpark(thread);
                    thread.interrupt();
                }

                if (i % 10 == 9) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Shutting down thread is executing ").append(thread)
                            .append(", " + "handlerCount=").append(handlerCount()).append("\n");
                    Jvm.trimStackTrace(sb, thread.getStackTrace());
                    Jvm.warn().on(getClass(), sb.toString());
                    dumpRunningHandlers();
                }
            }
            running.set(false);

            pauser.unpause();

            Threads.shutdown(service, daemon);

            if (thread != null)
                thread.interrupt();

        } finally {
            highHandlers.clear();
            mediumHandlers.clear();
            mediumHandlersArray = NO_EVENT_HANDLERS;
            daemonHandlers.clear();
            timerHandlers.clear();
            newHandlerQueue.clear();
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

        for (Object o; (o = newHandlerQueue.poll()) != null; ) {
            Jvm.warn().on(getClass(), "Handler in newHandlerQueue was not accepted before close " + o);
            Closeable.closeQuietly(o);
        }
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
