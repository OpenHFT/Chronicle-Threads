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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.Time;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.openhft.chronicle.threads.VanillaEventLoop.NO_CPU;

/*
 * Created by peter.lawrey on 22/01/15.
 */
public class EventGroup implements EventLoop {

    static final long REPLICATION_MONITOR_INTERVAL_MS = Long.getLong
            ("REPLICATION_MONITOR_INTERVAL_MS", SECONDS.toMillis(15));

    static final long MONITOR_INTERVAL_MS = Long.getLong("MONITOR_INTERVAL_MS", 200);

    static final int CONC_THREADS = Integer.getInteger("CONC_THREADS", (Runtime.getRuntime().availableProcessors() + 2) / 2);

    private static final Integer REPLICATION_EVENT_PAUSE_TIME = Integer.getInteger
            ("replicationEventPauseTime", 20);
    @NotNull
    final EventLoop monitor;
    @NotNull
    final VanillaEventLoop core;
    @NotNull
    final BlockingEventLoop blocking;
    @NotNull
    private final Pauser pauser;
    private final boolean binding;
    private final int bindingCpuReplication;
    private final String name;
    private VanillaEventLoop replication;
    @NotNull
    private VanillaEventLoop[] concThreads = new VanillaEventLoop[CONC_THREADS];
    private Supplier<Pauser> concThreadPauserSupplier = () -> Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);
    private boolean daemon;

    /**
     * Create an EventGroup
     *
     * @param daemon                whether to create threads as daemon
     * @param pauser                pauser to use
     * @param binding               whether to bind core event loop to current core
     * @param bindingCpuCore        CPU to bind core event loop to. Supersedes binding above
     * @param bindingCpuReplication CPU to bind replication event loop to. -1 means no binding
     * @param name                  name of event group. Any created threads are named after this
     */
    public EventGroup(boolean daemon, @NotNull Pauser pauser, boolean binding, int bindingCpuCore, int bindingCpuReplication, String name) {
        this.daemon = daemon;
        this.pauser = pauser;
        this.binding = binding;
        this.bindingCpuReplication = bindingCpuReplication;
        this.name = name;

        core = new VanillaEventLoop(this, name + "core-event-loop", pauser, 1, daemon, binding, bindingCpuCore);
        monitor = new MonitorEventLoop(this, name, Pauser.millis(Integer.getInteger("monitor.interval", 10)));
        monitor.addHandler(new PauserMonitor(pauser, name + "core pauser", 30));
        blocking = new BlockingEventLoop(this, name + "blocking-event-loop");
    }

    public EventGroup(boolean daemon) {
        this(daemon, false);
    }

    public EventGroup(boolean daemon, boolean binding) {
        this(daemon, Pauser.balanced(), binding);
    }

    public EventGroup(boolean daemon, @NotNull Pauser pauser, boolean binding) {
        this(daemon, pauser, binding, NO_CPU, NO_CPU, "");
    }

    public EventGroup(boolean daemon, @NotNull Pauser pauser, boolean binding, String name) {
        this(daemon, pauser, binding, NO_CPU, NO_CPU, name);
    }

    static int hash(int n, int mod) {
        n = (n >>> 23) ^ (n >>> 9) ^ n;
        n = (n & 0x7FFF_FFFF) % mod;
        return n;
    }

    public void setConcThreadPauserSupplier(Supplier<Pauser> supplier) {
        concThreadPauserSupplier = supplier;
    }

    synchronized VanillaEventLoop getReplication() {
        if (replication == null) {
            Pauser pauser = Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);
            replication = new VanillaEventLoop(this, name + "replication-event-loop", pauser, REPLICATION_EVENT_PAUSE_TIME, true, binding, bindingCpuReplication);
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, replication));
            replication.start();
            monitor.addHandler(new PauserMonitor(pauser, name + "replication pauser", 60));
        }
        return replication;
    }

    private synchronized VanillaEventLoop getConcThread(int n) {
        if (concThreads[n] == null) {
            Pauser pauser = concThreadPauserSupplier.get();
            concThreads[n] = new VanillaEventLoop(this, name + "conc-event-loop-" + n, pauser, REPLICATION_EVENT_PAUSE_TIME, daemon, binding, NO_CPU);
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, concThreads[n]));
            concThreads[n].start();
            monitor.addHandler(new PauserMonitor(pauser, name + "conc-event-loop-" + n + " pauser", 60));
        }
        return concThreads[n];
    }

    @Override
    public void awaitTermination() {
        core.awaitTermination();
        blocking.awaitTermination();
        monitor.awaitTermination();
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
    }

    @Override
    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        addHandler(handler);
    }

    @Override
    public void addHandler(@NotNull EventHandler handler) {
        HandlerPriority t1 = handler.priority();
        switch (t1) {
            case HIGH:
            case MEDIUM:
            case TIMER:
            case DAEMON:
                core.addHandler(handler);
                break;

            case MONITOR:
                monitor.addHandler(handler);
                break;

            case BLOCKING:
                blocking.addHandler(handler);
                break;

            // used only for replication, this is so replication can run in its own thread
            case REPLICATION:
                getReplication().addHandler(handler);
                break;

            case CONCURRENT: {
                int n = hash(handler.hashCode(), CONC_THREADS);
                getConcThread(n).addHandler(handler);
                break;
            }

            default:
                throw new IllegalArgumentException("Unknown priority " + handler.priority());
        }
    }

    @Override
    public void start() {
        if (!core.isAlive()) {
            core.start();

            monitor.start();
            // this checks that the core threads have stalled
            monitor.addHandler(new LoopBlockMonitor(MONITOR_INTERVAL_MS, EventGroup.this.core));
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
        core.stop();
        blocking.stop();
    }

    @Override
    public boolean isClosed() {
        return core.isClosed();
    }

    @Override
    public boolean isAlive() {
        return core.isAlive();
    }

    @Override
    public void close() {
        stop();
        Closeable.closeQuietly(
                monitor,
                blocking,
                core);

        VanillaEventLoop replication = this.replication;
        if (replication != null) Closeable.closeQuietly(replication);
        Closeable.closeQuietly(concThreads);
    }

    class LoopBlockMonitor implements EventHandler {
        private final long monitoryIntervalMs;
        @NotNull
        private final VanillaEventLoop eventLoop;
        long lastInterval = 1;

        public LoopBlockMonitor(long monitoryIntervalMs, @NotNull final VanillaEventLoop eventLoop) {
            this.monitoryIntervalMs = monitoryIntervalMs;
            assert eventLoop != null;
            this.eventLoop = eventLoop;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {

            long loopStartMS = eventLoop.loopStartMS();
            if (loopStartMS <= 0 || loopStartMS == Long.MAX_VALUE)
                return false;
            if (loopStartMS == Long.MAX_VALUE - 1) {
                Jvm.warn().on(getClass(), "Monitoring a task which has finished " + eventLoop);
                throw new InvalidEventHandlerException();
            }
            long now = Time.currentTimeMillis();
            long blockingTimeMS = now - loopStartMS;
            long blockingInterval = blockingTimeMS / ((monitoryIntervalMs + 1) / 2);

            if (blockingInterval > lastInterval && !Jvm.isDebug() && eventLoop.isAlive()) {
                eventLoop.dumpRunningState(eventLoop.name() + " thread has blocked for "
                                + blockingTimeMS + " ms.",
                        // check we are still in the loop.
                        () -> eventLoop.loopStartMS() == loopStartMS);

            } else {
                lastInterval = Math.max(1, blockingInterval);
            }
            return false;
        }
    }
}
