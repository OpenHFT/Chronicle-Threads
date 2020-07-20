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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.Time;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.openhft.chronicle.threads.VanillaEventLoop.NO_CPU;

public class EventGroup
        extends AbstractCloseable
        implements EventLoop {

    public static final int CONC_THREADS = Integer.getInteger("eventGroup.conc.threads",
            Integer.getInteger("CONC_THREADS", Runtime.getRuntime().availableProcessors() / 4));
    private static final long REPLICATION_MONITOR_INTERVAL_MS = Long.getLong("REPLICATION_MONITOR_INTERVAL_MS", 500);
    private static final long MONITOR_INTERVAL_MS = Long.getLong("MONITOR_INTERVAL_MS", 100);
    private static final Integer REPLICATION_EVENT_PAUSE_TIME = Integer.getInteger("replicationEventPauseTime", 20);
    private final AtomicInteger counter = new AtomicInteger();
    @NotNull
    private final EventLoop monitor;
    private final CoreEventLoop core;
    private final BlockingEventLoop blocking;
    @NotNull
    private final Pauser pauser;
    private final Pauser concPauser;
    private final String concBinding;
    private final String bindingReplication;
    private final String name;
    private final Set<HandlerPriority> priorities;
    @NotNull
    private final VanillaEventLoop[] concThreads;
    private final MilliPauser milliPauser = Pauser.millis(50);
    private final boolean daemon;

    private VanillaEventLoop replication;

    /**
     * Create an EventGroup
     *
     * @param daemon                whether to create threads as daemon
     * @param pauser                pauser to use
     * @param binding               whether to bind core event loop to current core
     * @param bindingCpuCore        CPU to bind core event loop to. Supersedes binding above
     * @param bindingCpuReplication CPU to bind replication event loop to. -1 means no binding
     * @param name                  name of event group. Any created threads are named after this
     * @param concThreads           number of concurrent threads to support
     */
    @Deprecated
    public EventGroup(final boolean daemon,
                      @NotNull final Pauser pauser,
                      final boolean binding,
                      final int bindingCpuCore,
                      final int bindingCpuReplication,
                      final String name,
                      final int concThreads) {
        this(daemon,
                pauser,
                bindingCpuCore != -1 ? Integer.toString(bindingCpuCore) : binding ? "any" : "none",
                bindingCpuReplication != -1 ? Integer.toString(bindingCpuReplication) : "none",
                name,
                concThreads,
                EnumSet.noneOf(HandlerPriority.class));
    }

    /**
     * Create an EventGroup
     *
     * @param daemon             whether to create threads as daemon
     * @param pauser             pauser to use
     * @param binding            CPU to bind core event loop to.
     * @param bindingReplication CPU to bind replication event loop to. -1 means no binding
     * @param name               name of event group. Any created threads are named after this
     * @param concThreadsNum     number of concurrent threads to support
     * @param priorities         priorities that we expect to support
     */
    public EventGroup(final boolean daemon,
                      final @NotNull Pauser pauser,
                      final String binding,
                      final String bindingReplication,
                      final String name,
                      final int concThreadsNum,
                      final Set<HandlerPriority> priorities) {
        this(daemon,
                pauser,
                binding,
                bindingReplication,
                name,
                concThreadsNum,
                "none",
                Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME),
                priorities);
    }

    public EventGroup(final boolean daemon,
                      @NotNull final Pauser pauser,
                      final String binding,
                      final String bindingReplication,
                      final String name,
                      final int concThreadsNum,
                      final String concBinding,
                      @NotNull final Pauser concPauser,
                      final Set<HandlerPriority> priorities) {
        this.daemon = daemon;
        this.pauser = pauser;
        this.concBinding = concBinding;
        this.concPauser = concPauser;
        this.bindingReplication = bindingReplication;
        this.name = name;
        this.priorities = EnumSet.copyOf(priorities);

        final Set<HandlerPriority> corePriorities = priorities.stream()
                .filter(VanillaEventLoop.ALLOWED_PRIORITIES::contains)
                .collect(Collectors.toSet());
        core = priorities.stream().anyMatch(VanillaEventLoop.ALLOWED_PRIORITIES::contains)
                ? isHighOrMedium(corePriorities)
                ? new MediumEventLoop(this, name + "core-event-loop", pauser, daemon, binding)
                : new VanillaEventLoop(this, name + "core-event-loop", pauser, 1, daemon, binding, priorities)
                : null;
        monitor = new MonitorEventLoop(this, name + "~monitor",
                Pauser.millis(Integer.getInteger("monitor.interval", 10)));
        if (core != null)
            monitor.addHandler(new PauserMonitor(pauser, name + "core-pauser", 30));
        blocking = priorities.contains(HandlerPriority.BLOCKING) ? new BlockingEventLoop(this, name + "blocking-event-loop") : null;
        concThreads = new VanillaEventLoop[priorities.contains(HandlerPriority.CONCURRENT) ? concThreadsNum : 0];
    }

    public EventGroup(final boolean daemon) {
        this(daemon, false);
    }

    public EventGroup(final boolean daemon, boolean binding) {
        this(daemon, Pauser.balanced(), binding);
    }

    public EventGroup(final boolean daemon, @NotNull final Pauser pauser, final boolean binding) {
        this(daemon, pauser, binding, NO_CPU, NO_CPU, "");
    }

    public EventGroup(final boolean daemon, @NotNull final Pauser pauser, final boolean binding, final String name) {
        this(daemon, pauser, binding, NO_CPU, NO_CPU, name);
    }

    public EventGroup(final boolean daemon, @NotNull final Pauser pauser, final boolean binding, final int bindingCpuCore, final int bindingCpuReplication, final String name) {
        this(daemon,
                pauser,
                bindingCpuCore != -1 ? Integer.toString(bindingCpuCore) : binding ? "any" : "none",
                bindingCpuReplication != -1 ? Integer.toString(bindingCpuReplication) : "none",
                name,
                CONC_THREADS,
                EnumSet.allOf(HandlerPriority.class));
    }

    private boolean isHighOrMedium(Set<HandlerPriority> priorities) {
        Set<HandlerPriority> copy = new HashSet<>(priorities);
        copy.remove(HandlerPriority.HIGH);
        copy.remove(HandlerPriority.MEDIUM);
        return copy.isEmpty();
    }

    @Override
    protected boolean threadSafetyCheck(boolean isUsed) {
        // thread safe
        return true;
    }

    private synchronized VanillaEventLoop getReplication() {
        if (replication == null) {
            Pauser pauser = Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);
            replication = new VanillaEventLoop(this, name + "replication-event-loop", pauser,
                    REPLICATION_EVENT_PAUSE_TIME, true, bindingReplication, EnumSet.of(HandlerPriority.REPLICATION));
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, replication));
            if (isAlive())
                replication.start();
            monitor.addHandler(new PauserMonitor(pauser, name + "replication pauser", 60));
        }
        return replication;
    }

    private synchronized VanillaEventLoop getConcThread(int n) {
        if (concThreads[n] == null) {
            concThreads[n] = new VanillaEventLoop(this, name + "conc-event-loop-" + n, concPauser,
                    REPLICATION_EVENT_PAUSE_TIME, daemon, concBinding, EnumSet.of(HandlerPriority.CONCURRENT));
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, concThreads[n]));
            if (isAlive())
                concThreads[n].start();
            monitor.addHandler(new PauserMonitor(pauser, name + "conc-event-loop-" + n + " pauser", 60));
        }
        return concThreads[n];
    }

    @Override
    public void awaitTermination() {
        monitor.awaitTermination();
        if (core != null)
            core.awaitTermination();
        if (blocking != null)
            blocking.awaitTermination();
        if (replication != null)
            replication.awaitTermination();
        for (VanillaEventLoop concThread : concThreads) {
            if (concThread != null)
                concThread.awaitTermination();
        }
    }

    @Override
    public void unpause() {
        pauser.unpause();
        if (blocking != null)
            blocking.unpause();
        if (replication != null)
            replication.unpause();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void addHandler(@NotNull final EventHandler handler) {
        throwExceptionIfClosed();

        HandlerPriority t1 = handler.priority();
        switch (t1) {
            case MONITOR:
                monitor.addHandler(handler);
                break;

            case HIGH:
            case MEDIUM:
            case TIMER:
            case DAEMON:
                if (core == null)
                    throw new IllegalStateException("Cannot add " + t1 + " " + handler + " to " + name);
                core.addHandler(handler);
                break;

            case BLOCKING:
                if (blocking == null)
                    throw new IllegalStateException("Cannot add BLOCKING " + handler + " to " + name);
                blocking.addHandler(handler);
                break;

            // used only for replication, this is so replication can run in its own thread
            case REPLICATION:
                if (!priorities.contains(HandlerPriority.REPLICATION))
                    throw new IllegalStateException("Cannot add REPLICATION " + handler + " to " + name);

                getReplication().addHandler(handler);
                break;

            case CONCURRENT: {
                if (concThreads.length == 0)
                    throw new IllegalStateException("Cannot add CONCURRENT " + handler + " to " + name);
                getConcThread(counter.getAndIncrement() % concThreads.length).addHandler(handler);
                break;
            }

            default:
                throw new IllegalArgumentException("Unknown priority " + handler.priority());
        }
    }

    public void setupTimeLimitMonitor(final long timeLimitNS, final LongSupplier timeOfStart) {
        throwExceptionIfClosed();

        // to cleanly shut down the runner, we cannot rely on Thread.interrupt as it
        // can cause nasty exceptions to bubble up from the guts of CQ
        addTimingMonitor(
                name + "-monitor",
                timeLimitNS,
                timeOfStart,
                core::thread);
    }

    public void addTimingMonitor(final String description,
                                 final long timeLimitNS,
                                 final LongSupplier timeSupplier,
                                 final Supplier<Thread> threadSupplier) {
        milliPauser.minPauseTimeMS((timeLimitNS + 999_999) / 1_000_000);
        addHandler(new ThreadMonitorEventHandler(description, timeLimitNS, timeSupplier, threadSupplier));
    }

    @Override
    public synchronized void start() {
        throwExceptionIfClosed();

        if (!isAlive()) {
            if (core != null)
                core.start();
            if (blocking != null)
                blocking.start();

            if (replication != null)
                replication.start();

            for (VanillaEventLoop concThread : concThreads) {
                if (concThread != null)
                    concThread.start();
            }

            monitor.start();
            // this checks that the core threads have stalled
            if (core != null)
                monitor.addHandler(new LoopBlockMonitor(MONITOR_INTERVAL_MS, EventGroup.this.core));

            while (!isAlive())
                Jvm.pause(1);
        }
    }

    @Override
    public void stop() {
        monitor.stop();
        if (replication != null)
            replication.stop();
        for (VanillaEventLoop concThread : concThreads) {
            if (concThread != null)
                concThread.stop();
        }
        if (core != null)
            core.stop();
        if (blocking != null)
            blocking.stop();
    }

    @Override
    public boolean isAlive() {
        return (core == null ? monitor : core).isAlive();
    }

    @Override
    protected void performClose() {
        stop();
        Closeable.closeQuietly(
                monitor,
                replication,
                blocking,
                core);

        Closeable.closeQuietly(concThreads);
    }

    static final class LoopBlockMonitor implements EventHandler {
        private final long monitoryIntervalMs;
        private final @NotNull CoreEventLoop eventLoop;
        private long printBlockTimeMS, interval;

        public LoopBlockMonitor(final long monitoryIntervalMs, @NotNull final CoreEventLoop eventLoop) {
            this.monitoryIntervalMs = monitoryIntervalMs;
            assert eventLoop != null;
            this.eventLoop = eventLoop;
            interval = printBlockTimeMS = monitoryIntervalMs;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            long loopStartMS = eventLoop.loopStartMS();
            if (loopStartMS <= 0 || loopStartMS == Long.MAX_VALUE) {
                if (interval != monitoryIntervalMs) {
                    Jvm.warn().on(getClass(), "Reset interval from " + interval);
                    interval = printBlockTimeMS = monitoryIntervalMs;
                }
                return false;
            }
            if (loopStartMS == Long.MAX_VALUE - 1) {
                Jvm.warn().on(getClass(), "Monitoring a task which has finished " + eventLoop);
                throw new InvalidEventHandlerException();
            }
            long now = Time.currentTimeMillis();
            long blockingTimeMS = now - loopStartMS;

            if (blockingTimeMS >= printBlockTimeMS && eventLoop.isAlive()) {
                eventLoop.dumpRunningState(eventLoop.name() + " thread has blocked for "
                                + blockingTimeMS + " ms.",
                        // check we are still in the loop.
                        () -> eventLoop.loopStartMS() == loopStartMS);
                printBlockTimeMS += interval;
                interval *= 1.41;
                if (interval > 20 * monitoryIntervalMs) {
                    interval = monitoryIntervalMs * 20;
                    printBlockTimeMS -= printBlockTimeMS % interval;
                }
            }
            return false;
        }

        @NotNull
        @Override
        public HandlerPriority priority() {
            return HandlerPriority.MONITOR;
        }

        @Override
        public String toString() {
            return "LoopBlockMonitor<" + eventLoop.name() + '>';
        }
    }
}