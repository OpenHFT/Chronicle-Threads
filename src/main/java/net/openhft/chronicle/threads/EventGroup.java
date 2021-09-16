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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.threads.internal.EventLoopThreadHolder;
import net.openhft.chronicle.threads.internal.ThreadMonitorHarness;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;
import static net.openhft.chronicle.threads.VanillaEventLoop.NO_CPU;

public class EventGroup
        extends AbstractLifecycleEventLoop
        implements EventLoop {

    public static final int CONC_THREADS = Integer.getInteger("eventGroup.conc.threads",
            Integer.getInteger("CONC_THREADS", Math.max(1, Runtime.getRuntime().availableProcessors() / 4)));
    private static final long REPLICATION_MONITOR_INTERVAL_MS = Long.getLong("REPLICATION_MONITOR_INTERVAL_MS", 500);
    private static final long MONITOR_INTERVAL_MS = Long.getLong("MONITOR_INTERVAL_MS", 100);
    private static final Integer REPLICATION_EVENT_PAUSE_TIME = Integer.getInteger("replicationEventPauseTime", 20);
    private static final boolean ENABLE_LOOP_BLOCK_MONITOR = !Jvm.getBoolean("disableLoopBlockMonitor");
    private static final long WAIT_TO_START_MS = Integer.getInteger("eventGroup.wait.to.start.ms", 1_000);
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
    private final Set<HandlerPriority> priorities;
    @NotNull
    private final List<VanillaEventLoop> concThreads = new CopyOnWriteArrayList<>();
    private final MilliPauser milliPauser = Pauser.millis(50);
    private final boolean daemon;

    private VanillaEventLoop replication;

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
        super(name);
        this.daemon = daemon;
        this.pauser = pauser;
        this.concBinding = concBinding;
        this.concPauser = concPauser;
        this.bindingReplication = bindingReplication;
        this.priorities = EnumSet.copyOf(priorities);
        List<Object> closeable = new ArrayList<>();
        try {
            final Set<HandlerPriority> corePriorities = priorities.stream()
                    .filter(VanillaEventLoop.ALLOWED_PRIORITIES::contains)
                    .collect(Collectors.toSet());
            core = priorities.stream().anyMatch(VanillaEventLoop.ALLOWED_PRIORITIES::contains)
                    ? corePriorities.equals(EnumSet.of(HandlerPriority.MEDIUM))
                    ? new MediumEventLoop(this, name + "core-event-loop", pauser, daemon, binding)
                    : new VanillaEventLoop(this, name + "core-event-loop", pauser, 1, daemon, binding, priorities)
                    : null;
            closeable.add(core);
            monitor = new MonitorEventLoop(this, name + "~monitor",
                    Pauser.millis(Integer.getInteger("monitor.interval", 10)));
            closeable.add(monitor);
            if (core != null)
                monitor.addHandler(new PauserMonitor(pauser, name + "core-pauser", 300));
            blocking = priorities.contains(HandlerPriority.BLOCKING) ? new BlockingEventLoop(this, name + "blocking-event-loop") : null;
            closeable.add(blocking);
            if (priorities.contains(HandlerPriority.CONCURRENT))
                IntStream.range(0, concThreadsNum).forEach(i -> concThreads.add(null));

            disableThreadSafetyCheck(true);

            closeable.clear();
        } finally {
            closeQuietly(closeable);
        }
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

    private synchronized VanillaEventLoop getReplication() {
        if (replication == null) {
            Pauser pauser = Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);
            replication = new VanillaEventLoop(this, name + "replication-event-loop", pauser,
                    REPLICATION_EVENT_PAUSE_TIME, true, bindingReplication, EnumSet.of(HandlerPriority.REPLICATION, HandlerPriority.REPLICATION_TIMER));

            addThreadMonitoring(REPLICATION_MONITOR_INTERVAL_MS, replication);
            if (isAlive())
                replication.start();
            monitor.addHandler(new PauserMonitor(pauser, name + "replication pauser", 300));
        }
        return replication;
    }

    private void addThreadMonitoring(long replicationMonitorIntervalMs, CoreEventLoop replication) {
        if (ENABLE_LOOP_BLOCK_MONITOR)
            monitor.addHandler(new ThreadMonitorHarness(new EventLoopThreadHolder(
                    TimeUnit.NANOSECONDS.convert(replicationMonitorIntervalMs, TimeUnit.MILLISECONDS), replication)));
    }

    private synchronized VanillaEventLoop getConcThread(int n) {
        VanillaEventLoop loop = concThreads.get(n);
        if (loop == null) {
            loop = new VanillaEventLoop(this, name + "conc-event-loop-" + n, concPauser,
                    REPLICATION_EVENT_PAUSE_TIME, daemon, concBinding, EnumSet.of(HandlerPriority.CONCURRENT));
            concThreads.set(n, loop);
            addThreadMonitoring(REPLICATION_MONITOR_INTERVAL_MS, loop);
            if (isAlive())
                loop.start();
            monitor.addHandler(new PauserMonitor(pauser, name + "conc-event-loop-" + n + " pauser", 300));
        }
        return loop;
    }

    @Override
    public void unpause() {
        pauser.unpause();
        if (replication != null)
            replication.unpause();
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
            case REPLICATION_TIMER:
                if (t1 == HandlerPriority.REPLICATION && !priorities.contains(HandlerPriority.REPLICATION))
                    throw new IllegalStateException("Cannot add REPLICATION " + handler + " to " + name);

                if (t1 == HandlerPriority.REPLICATION_TIMER && !priorities.contains(HandlerPriority.REPLICATION_TIMER))
                    throw new IllegalStateException("Cannot add REPLICATION_TIMER " + handler + " to " + name);

                getReplication().addHandler(handler);
                break;

            case CONCURRENT: {
                if (concThreads.isEmpty())
                    throw new IllegalStateException("Cannot add CONCURRENT " + handler + " to " + name);
                getConcThread(counter.getAndIncrement() % concThreads.size()).addHandler(handler);
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
        addHandler(ThreadMonitors.forThread(description, timeLimitNS, timeSupplier, threadSupplier));
    }

    /**
     * Starts the event loop and waits for the core (or monitor) event loop thread to start before returning
     * (or timing out)
     */
    @Override
    protected void performStart() {
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
            addThreadMonitoring(MONITOR_INTERVAL_MS, core);

        // wait for core to start, We use a TimingPauser, previously we waited for ever
        TimingPauser timeoutPauser = Pauser.sleepy();
        while (!isAlive()) {
            try {
                timeoutPauser.pause(WAIT_TO_START_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw Jvm.rethrow(e);
            }
        }
    }

    @Override
    protected void performStopFromNew() {
        performStop();
    }

    @Override
    protected void performStopFromStarted() {
        performStop();
    }

    private void performStop() {
        monitor.stop();
        EventLoops.stopAll(concThreads, replication, core, blocking);
    }

    @Override
    public boolean isAlive() {
        return (core == null ? monitor : core).isAlive();
    }

    @Override
    protected void performClose() {
        super.performClose();
        closeQuietly(
                core,
                monitor,
                replication,
                blocking
        );

        closeQuietly(concThreads);
        awaitTermination();
    }
}