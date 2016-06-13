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
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * Created by peter.lawrey on 22/01/15.
 */
public class VanillaEventLoop implements EventLoop, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(VanillaEventLoop.class);
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
    private final String name;
    private final boolean binding;
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
     * @param binding to a cpu
     */
    public VanillaEventLoop(EventLoop parent,
                            String name,
                            Pauser pauser,
                            long timerIntervalMS,
                            boolean daemon,
                            boolean binding) {
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.timerIntervalMS = timerIntervalMS;
        this.binding = binding;
        loopStartMS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon));
    }

    public static void closeAll(List<EventHandler> handlers) {
        handlers.forEach(h -> {
            if (h instanceof Closeable) {
                Closeable.closeQuietly(h);
            } else {
                handlers.remove(h);
            }
        });
    }

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

    public void start() {
        if (closedHere != null)
            throw new IllegalStateException("Event Group has been closed", closedHere);
        if (!running.getAndSet(true))
            service.submit(this);
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    public void stop() {

    }

    @Override
    public boolean isClosed() {
        return closedHere != null;
    }

    public void addHandler(@NotNull EventHandler handler) {
        addHandler(false, handler);
    }

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
            if (binding)
                affinityLock = AffinityLock.acquireLock();

            thread = Thread.currentThread();
            while (running.get()) {
                if (closedHere != null) {
                    LOG.trace("Closing down handlers");
                    closeAllHandlers();
                    runAllHighHandlers();
                    runAllMediumHandler();
                    runDaemonHandlers();
                    runTimerHandlers();
                    LOG.trace("Remaining handlers");
                    dumpRunningHandlers();
                    break;
                }
                boolean busy = false;
                if (highHandlers.isEmpty()) {
                    loopStartMS = Time.currentTimeMillis();
                    busy |= runAllMediumHandler();

                } else {
                    for (int i = 0; i < 10; i++) {
                        loopStartMS = Time.currentTimeMillis();
                        busy |= runAllHighHandlers();
                        busy |= runOneTenthLowHandler(i);
                    }
                }
                if (lastTimerNS + timerIntervalMS < loopStartMS) {
                    lastTimerNS = loopStartMS;
                    runTimerHandlers();
                }
                busy |= acceptNewHandlers();

                if (busy) {
                    pauser.reset();

                } else {
                    runDaemonHandlers();
                    // reset the loop timeout.
                    loopStartMS = Long.MAX_VALUE;
                    pauser.pause();
                }
            }
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), e);

        } finally {
            loopStartMS = Long.MAX_VALUE - 1;
            if (binding && affinityLock != null)
                affinityLock.release();
        }
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
                try {
                    highHandlers.remove(i--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!mediumHandlers.isEmpty())
                        throw e2;
                }

                closeQuietly(handler);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
    }

    @HotMethod
    private boolean runOneTenthLowHandler(int i) {
        boolean busy = false;
        for (int j = i; j < mediumHandlers.size(); j += 10) {
            EventHandler handler = mediumHandlers.get(j);
            try {
                boolean action = handler.action();
                if (action)
                    handler.action();

                busy |= action;
            } catch (InvalidEventHandlerException e) {
                try {
                    mediumHandlers.remove(j--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!mediumHandlers.isEmpty())
                        throw e2;
                }
                closeQuietly(handler);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
    }

    @HotMethod
    private boolean runAllMediumHandler() {
        boolean busy = false;
        for (int j = 0; j < mediumHandlers.size(); j++) {
            EventHandler handler = mediumHandlers.get(j);
            try {
                busy |= handler.action();
            } catch (InvalidEventHandlerException e) {
                try {
                    mediumHandlers.remove(j--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!mediumHandlers.isEmpty())
                        throw e2;
                }
                closeQuietly(handler);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        return busy;
    }

    @HotMethod
    private void runTimerHandlers() {
        for (int i = 0; i < timerHandlers.size(); i++) {
            EventHandler handler = timerHandlers.get(i);
            try {
                handler.action();
            } catch (InvalidEventHandlerException e) {
                try {
                    timerHandlers.remove(i--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!timerHandlers.isEmpty())
                        throw e2;
                }
                closeQuietly(handler);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
    }

    @HotMethod
    private void runDaemonHandlers() {
        for (int i = 0; i < daemonHandlers.size(); i++) {
            EventHandler handler = daemonHandlers.get(i);
            try {
                handler.action();
            } catch (InvalidEventHandlerException e) {
                try {
                    daemonHandlers.remove(i--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!daemonHandlers.isEmpty())
                        throw e2;
                }
                closeQuietly(handler);

            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
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
            case MEDIUM:
                if (!mediumHandlers.contains(handler))
                    mediumHandlers.add(handler);
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
            closedHere = new Throwable("Closed here");

            closeAllHandlers();

            if (thread == null)
                return;

            for (int i = 0; i < 30; i++) {
                pauser.unpause();

                Jvm.pause(10);
                if (handlerCount() == 0)
                    break;
                if (i % 10 == 4)
                    thread.interrupt();

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
            service.shutdown();
            pauser.unpause();
            if (thread != null)
                thread.interrupt();

            if (!(service.awaitTermination(1, TimeUnit.SECONDS))) {
                Thread thread = this.thread;
                if (thread != null) {
                    StackTraceElement[] stackTrace = thread.getStackTrace();
                    StringBuilder sb = new StringBuilder(thread + " still running ");
                    Jvm.trimStackTrace(sb, stackTrace);
                    LOG.info(sb.toString());
                }
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            Threads.shutdown(service);

        } finally {
            highHandlers.clear();
            mediumHandlers.clear();
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
        Optional.ofNullable(newHandler.get()).ifPresent(Closeable::closeQuietly);

        for (Object o; (o = newHandlerQueue.poll()) != null; )
            Closeable.closeQuietly(o);
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
