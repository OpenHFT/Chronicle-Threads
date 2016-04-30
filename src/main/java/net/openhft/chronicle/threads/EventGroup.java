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
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.Time;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Created by peter.lawrey on 22/01/15.
 */
public class EventGroup implements EventLoop {

    static final long REPLICATION_MONITOR_INTERVAL_MS = Long.getLong
            ("REPLICATION_MONITOR_INTERVAL_MS", SECONDS.toMillis(15));

    static final long MONITOR_INTERVAL_MS = Long.getLong("MONITOR_INTERVAL_MS", 200);

    static final int IO_THREADS = Integer.getInteger("IO_THREADS", (Runtime.getRuntime().availableProcessors() + 3) / 4);

    private static final Logger LOG = LoggerFactory.getLogger(EventGroup.class);
    private static final Integer REPLICATION_EVENT_PAUSE_TIME = Integer.getInteger
            ("replicationEventPauseTime", 20);
    final EventLoop monitor;
    @NotNull
    final VanillaEventLoop core;
    final BlockingEventLoop blocking;
    private final Consumer<Throwable> onThrowable;
    @NotNull
    private final Pauser pauser;
    private final boolean binding;
    private VanillaEventLoop _replication;
    private VanillaEventLoop[] ioThreads = new VanillaEventLoop[IO_THREADS];

    public EventGroup(boolean daemon, Consumer<Throwable> onThrowable, Pauser pauser, boolean binding) {
        this.onThrowable = onThrowable;
        this.pauser = pauser;
        this.binding = binding;

        core = new VanillaEventLoop(this, "core-event-loop", pauser, 1, daemon, onThrowable, binding);
        monitor = new MonitorEventLoop(this, new LongPauser(0, 0, 1, 1, TimeUnit.SECONDS), onThrowable);
        monitor.addHandler(new PauserMonitor(pauser, "core pauser", 30));
        blocking = new BlockingEventLoop(this, "blocking-event-loop", onThrowable);
    }

    public EventGroup(boolean daemon, @NotNull Consumer<Throwable> onThrowable) {
        this(daemon, onThrowable, new LongPauser(500, 100, 500, Jvm.isDebug() ? 200_000 : 20_000, TimeUnit.MICROSECONDS), false);
    }

    public EventGroup(boolean daemon) {
        this(daemon, Throwable::printStackTrace);
    }

    static int hash(int n, int mod) {
        n = (n >>> 23) ^ (n >>> 9) ^ n;
        n = (n & 0x7FFF_FFFF) % mod;
        return n;
    }

    synchronized VanillaEventLoop getReplication() {
        if (_replication == null) {
            LongPauser pauser = new LongPauser(1, 50, 500, Jvm.isDebug() ? 200_000 : REPLICATION_EVENT_PAUSE_TIME * 1000, TimeUnit.MICROSECONDS);
            _replication = new VanillaEventLoop(this, "replication-event-loop", pauser, REPLICATION_EVENT_PAUSE_TIME, true, onThrowable, binding);
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, _replication));
            _replication.start();
            monitor.addHandler(new PauserMonitor(pauser, "replication pauser", 30));
        }
        return _replication;
    }

    synchronized VanillaEventLoop getIOThread(int n) {
        if (ioThreads[n] == null) {
            LongPauser pauser = new LongPauser(1, 50, 500, Jvm.isDebug() ? 200_000 : REPLICATION_EVENT_PAUSE_TIME * 1000, TimeUnit.MICROSECONDS);
            _replication = new VanillaEventLoop(this, "io-event-loop-" + n, pauser, REPLICATION_EVENT_PAUSE_TIME, true, onThrowable, binding);
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, _replication));
            _replication.start();
            monitor.addHandler(new PauserMonitor(pauser, "io-event-loop-" + n + " pauser", 30));
        }
        return _replication;
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    @Override
    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        addHandler(handler);
    }

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

            case IO: {
                int n = hash(handler.hashCode(), IO_THREADS);
                getIOThread(n).addHandler(handler);
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
        if (_replication != null)
            _replication.stop();
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
        monitor.close();
        blocking.close();
        core.close();
        if (_replication != null) _replication.close();

    }

    class LoopBlockMonitor implements EventHandler {
        private final long monitoryIntervalMs;
        private final VanillaEventLoop eventLoop;
        long lastInterval = 1;

        public LoopBlockMonitor(long monitoryIntervalMs, final VanillaEventLoop eventLoop) {
            this.monitoryIntervalMs = monitoryIntervalMs;
            this.eventLoop = eventLoop;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {

            long loopStartMS = eventLoop.loopStartMS();
            if (loopStartMS <= 0 || loopStartMS == Long.MAX_VALUE)
                return false;
            if (loopStartMS == Long.MAX_VALUE - 1) {
                LOG.warn("Monitoring a task which has finished");
                throw new InvalidEventHandlerException();
            }
            long now = Time.currentTimeMillis();
            long blockingTimeMS = now - loopStartMS;
            long blockingInterval = blockingTimeMS / (monitoryIntervalMs / 2);

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
