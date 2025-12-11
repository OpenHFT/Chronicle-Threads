/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.threads.internal.EventLoopStateRenderer;
import net.openhft.chronicle.threads.internal.EventLoopThreadHolder;
import net.openhft.chronicle.threads.internal.ThreadMonitorHarness;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * Coordinates a set of child event loops and routes handlers to them by
 * {@link HandlerPriority priority}. The constructor allocates the monitor and,
 * when required, the core and blocking loops. Replication and concurrent loops
 * are created lazily when handlers with those priorities are installed.
 * <p>
 * The recommended way to create an instance is via {@link EventGroupBuilder}.
 * The builder defaults to daemon threads, a balanced pauser and a number of
 * concurrent loops equal to {@link #CONC_THREADS}. Monitoring is enabled by
 * default and can be disabled with the system property
 * {@code disableLoopBlockMonitor=true}.
 * <p>
 * {@link #start()} starts all current child loops and waits for the core (or
 * monitor when no core exists) to become alive. {@link #stop()} stops the loops
 * and waits for them to finish. Loops are not expected to be restarted once
 * stopped.
 * <p>
 * Handlers installed via {@link #addHandler(EventHandler)} are routed to the
 * appropriate child loop. Unsupported priorities cause an
 * {@link IllegalArgumentException}.
 * <p>
 * Example:
 * <pre>
 * EventGroup eg = EventGroup.builder()
 *         .withName("example")
 *         .build();
 * eg.start();
 * </pre>
 */
public class EventGroup extends AbstractLifecycleEventLoop implements EventLoop {

    /**
     * Default number of concurrent event loop threads.
     */
    public static final int CONC_THREADS = Jvm.getInteger("eventGroup.conc.threads",
            Jvm.getInteger("CONC_THREADS", Math.max(1, Runtime.getRuntime().availableProcessors() / 4)));
    private static final long REPLICATION_MONITOR_INTERVAL_MS = Jvm.getLong("REPLICATION_MONITOR_INTERVAL_MS", 500L);
    private static final long MONITOR_INTERVAL_MS = Jvm.getLong("MONITOR_INTERVAL_MS", 100L);
    static final Integer REPLICATION_EVENT_PAUSE_TIME = Jvm.getInteger("replicationEventPauseTime", 20);
    private static final boolean ENABLE_LOOP_BLOCK_MONITOR = !Jvm.getBoolean("disableLoopBlockMonitor");
    private static final long WAIT_TO_START_MS = Jvm.getInteger("eventGroup.wait.to.start.ms", 2_000);
    private final AtomicInteger counter = new AtomicInteger();
    @NotNull
    private final MonitorEventLoop monitor;
    private final CoreEventLoop core;
    private final BlockingEventLoop blocking;
    @NotNull
    private final Pauser pauser;
    @NotNull
    private final Supplier<Pauser> concPauserSupplier;
    private final String concBinding;
    private final String bindingReplication;
    private final Set<HandlerPriority> priorities;
    @NotNull
    private final List<VanillaEventLoop> concThreads = new CopyOnWriteArrayList<>();
    private final boolean daemon;

    private final Pauser replicationPauser;
    private VanillaEventLoop replication;

    /**
     * Builds an EventGroup with the supplied configuration. Prefer {@link EventGroupBuilder}.
     *
     * @param daemon                 whether worker threads should be daemon threads
     * @param pauser                 pauser used by the core event loop
     * @param replicationPauser      pauser used by the replication loop
     * @param binding                CPU affinity for the core loop
     * @param bindingReplication     CPU affinity for the replication loop
     * @param name                   base name for threads
     * @param concThreadsNum         number of concurrent event loops to provision
     * @param concBinding            CPU affinity for concurrent loops
     * @param concPauserSupplier     supplier for concurrent pausers
     * @param priorities             handler priorities enabled for this group
     * @param blockingPauserSupplier supplier for the blocking loop pauser
     */
    @Deprecated(/* Instead use EventGroupBuilder. TODO: make package-private and undeprecate in x.28, as only EventGroupBuilder should be using */)
    @SuppressWarnings({"this-escape", "deprecation"})
    public EventGroup(final boolean daemon,
                      @NotNull final Pauser pauser,
                      final Pauser replicationPauser,
                      final String binding,
                      final String bindingReplication,
                      @NotNull final String name,
                      final int concThreadsNum,
                      final String concBinding,
                      @NotNull final Supplier<Pauser> concPauserSupplier,
                      final Set<HandlerPriority> priorities,
                      @NotNull final Supplier<Pauser> blockingPauserSupplier) {
        super(name);
        this.daemon = daemon;
        this.pauser = pauser;
        this.replicationPauser = replicationPauser;
        this.concBinding = concBinding;
        this.concPauserSupplier = concPauserSupplier;
        this.bindingReplication = bindingReplication;
        this.priorities = EnumSet.copyOf(priorities);
        List<Object> closeable = new ArrayList<>();
        try {
            final Set<HandlerPriority> corePriorities = priorities.stream()
                    .filter(VanillaEventLoop.ALLOWED_PRIORITIES::contains)
                    .collect(Collectors.toSet());
            core = priorities.stream().anyMatch(VanillaEventLoop.ALLOWED_PRIORITIES::contains)
                    ? corePriorities.equals(EnumSet.of(HandlerPriority.MEDIUM))
                    ? new MediumEventLoop(this, nameWithSlash() + "core-event-loop", pauser, daemon, binding)
                    : new VanillaEventLoop(this, nameWithSlash() + "core-event-loop", pauser, 1, daemon, binding, priorities)
                    : null;
            closeable.add(core);
            monitor = new MonitorEventLoop(this, nameWithSlash() + "~monitor",
                    Pauser.millis(Integer.getInteger("monitor.interval", 10)));
            closeable.add(monitor);
            if (core != null) {
                monitor.addHandler(PauserMonitorFactory.load().pauserMonitor(pauser, nameWithSlash() + "core-pauser", 300));
                long samplerMicros = Integer.getInteger("sampler.micros", 0);
                if (pauser instanceof TimingPauser && samplerMicros > 0)
                    setupTimeLimitMonitor(samplerMicros * 1000, core::loopStartNS);
            }
            blocking = priorities.contains(HandlerPriority.BLOCKING) ? new BlockingEventLoop(this, nameWithSlash() + "blocking-event-loop", blockingPauserSupplier) : null;
            closeable.add(blocking);
            if (priorities.contains(HandlerPriority.CONCURRENT))
                IntStream.range(0, concThreadsNum).forEach(i -> concThreads.add(null));

            singleThreadedCheckDisabled(true);

            closeable.clear();
        } finally {
            closeQuietly(closeable);
        }
    }

    /**
     * Create an EventGroup builder
     *
     * @return A new {@link EventGroupBuilder}
     */
    public static EventGroupBuilder builder() {
        return EventGroupBuilder.builder();
    }

    @SuppressWarnings("deprecation")
    private synchronized VanillaEventLoop getReplication() {
        if (replication == null) {
            final Pauser newReplicationPauser = replicationPauser != null ? replicationPauser : Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);
            replication = new VanillaEventLoop(this, nameWithSlash() + "replication-event-loop", newReplicationPauser,
                    REPLICATION_EVENT_PAUSE_TIME, daemon, bindingReplication, EnumSet.of(HandlerPriority.REPLICATION, HandlerPriority.REPLICATION_TIMER));

            addThreadMonitoring(REPLICATION_MONITOR_INTERVAL_MS, replication);
            if (isAlive())
                replication.start();
            monitor.addHandler(PauserMonitorFactory.load().pauserMonitor(newReplicationPauser, nameWithSlash() + "replication pauser", 300));
        }
        return replication;
    }

    private void addThreadMonitoring(long replicationMonitorIntervalMs, CoreEventLoop replication) {
        if (ENABLE_LOOP_BLOCK_MONITOR)
            monitor.addHandler(new ThreadMonitorHarness(new EventLoopThreadHolder(
                    TimeUnit.NANOSECONDS.convert(replicationMonitorIntervalMs, TimeUnit.MILLISECONDS), replication)));
    }

    @SuppressWarnings("deprecation")
    private synchronized VanillaEventLoop getConcThread(int n) {
        VanillaEventLoop loop = concThreads.get(n);
        if (loop == null) {
            loop = new VanillaEventLoop(this, nameWithSlash() + "conc-event-loop-" + n, concPauserSupplier.get(),
                    REPLICATION_EVENT_PAUSE_TIME, daemon, concBinding, EnumSet.of(HandlerPriority.CONCURRENT));
            concThreads.set(n, loop);
            addThreadMonitoring(REPLICATION_MONITOR_INTERVAL_MS, loop);
            if (isAlive())
                loop.start();
            monitor.addHandler(PauserMonitorFactory.load().pauserMonitor(pauser, nameWithSlash() + "conc-event-loop-" + n + " pauser", 300));
        }
        return loop;
    }

    @Override
    public void unpause() {
        pauser.unpause();
        if (replication != null)
            replication.unpause();
    }

    /**
     * Installs the handler on the child loop that matches its
     * {@link HandlerPriority}. Handlers may be added before or after the
     * group is started. Priority to loop mapping is as follows:
     * <ul>
     * <li>{@link HandlerPriority#MONITOR} - monitor loop</li>
     * <li>{@link HandlerPriority#HIGH}, {@link HandlerPriority#MEDIUM},
     * {@link HandlerPriority#TIMER} and {@link HandlerPriority#DAEMON} - core loop</li>
     * <li>{@link HandlerPriority#BLOCKING} - blocking loop</li>
     * <li>{@link HandlerPriority#REPLICATION} and
     * {@link HandlerPriority#REPLICATION_TIMER} - replication loop</li>
     * <li>{@link HandlerPriority#CONCURRENT} - one of the concurrent loops</li>
     * </ul>
     * If the relevant loop was not configured an {@link IllegalStateException}
     * is thrown. Unknown priorities result in an
     * {@link IllegalArgumentException}.
     */
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

    /**
     * Adds a monitor that logs a stack trace if the core loop runs longer than
     * the supplied time limit. The {@code timeOfStart} supplier should return
     * the time the action began in nano-seconds.
     *
     * @param timeLimitNS time limit in nanoseconds
     * @param timeOfStart supplier returning when the monitored task began
     */
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

    /**
     * Installs a {@link ThreadMonitor} on the monitor loop to observe a thread
     * for long running tasks.
     *
     * @param description   label for log entries
     * @param timeLimitNS   threshold in nanoseconds for detecting long tasks
     * @param timeSupplier  supplies the start time of the monitored work
     * @param threadSupplier supplies the thread being monitored
     */
    public void addTimingMonitor(final String description,
                                 final long timeLimitNS,
                                 final LongSupplier timeSupplier,
                                 final Supplier<Thread> threadSupplier) {
        addHandler(ThreadMonitors.forThread(description, timeLimitNS, timeSupplier, threadSupplier));
    }

    /**
     * Starts the event loop and waits for the core (or monitor) event loop thread to start before returning
     * (or timing out)
     */
    @Override
    protected void performStart() {
        if (core != null) {
            core.start();
            waitToStart(core);
        }
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

        waitToStart(this);
    }

    private void waitToStart(EventLoop waitfor) {
        // wait for core to start, We use a TimingPauser, previously we waited forever
        TimingPauser timeoutPauser = Pauser.sleepy();
        long waitStartTimeMs = System.currentTimeMillis();
        while (!waitfor.isAlive()) {
            try {
                timeoutPauser.pause(WAIT_TO_START_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                long waitTime = System.currentTimeMillis() - waitStartTimeMs;
                String threadDump = renderThreadDump();
                Jvm.error().on(EventGroup.class, format("Timed out waiting for start! (waited %,dms)%n" +
                                "%s%n%n" +
                                "%s%n%n" +
                                "%s%n",
                        waitTime,
                        EventLoopStateRenderer.INSTANCE.render("Core", core),
                        EventLoopStateRenderer.INSTANCE.render("Monitor", monitor),
                        threadDump));
                String coreState = core == null
                        ? "Core loop not configured"
                        : EventLoopStateRenderer.INSTANCE.render("Core", core);
                String monitorState = EventLoopStateRenderer.INSTANCE.render("Monitor", monitor);
                String message = format("Timed out waiting %,dms for %s to start%n%s%n%n%s%n%n%s",
                        waitTime,
                        waitfor.name(),
                        coreState,
                        monitorState,
                        renderThreadDump());
                TimeoutException te = new TimeoutException(message);
                te.initCause(e);
                throw Jvm.rethrow(te);
            }
        }
    }

    private static String renderThreadDump() {
        final Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
        StringBuilder stringBuilder = new StringBuilder(256);
        stringBuilder.append("Thread dump at time of occurrence:\n\n");
        allStackTraces.forEach((key, value) -> {
            stringBuilder.append("------- Thread '").append(key.getName()).append("'\n");
            Threads.renderStackTrace(stringBuilder, value);
            stringBuilder.append("\n\n");
        });
        return stringBuilder.toString();
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

    /**
     * Returns {@code true} if the core loop thread is running. If no core loop
     * is configured the state of the monitor loop is reported instead.
     */
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

    @Override
    public boolean runsInsideCoreLoop() {
        return core != null && core.runsInsideCoreLoop();
    }

    @Override
    public boolean isRunningOnThread(Thread thread) {
        return core != null && core.isRunningOnThread(thread) ||
               blocking != null && blocking.isRunningOnThread(thread) ||
               monitor.isRunningOnThread(thread);
    }

    @Override
    public void privateGroup(boolean privateGroup) {
        super.privateGroup(privateGroup);
        if (core != null) {
            core.privateGroup(privateGroup);
        }
    }
}
