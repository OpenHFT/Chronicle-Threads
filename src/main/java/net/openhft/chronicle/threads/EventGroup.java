/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.util.Time;
import net.openhft.chronicle.threads.api.EventHandler;
import net.openhft.chronicle.threads.api.EventLoop;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static java.util.concurrent.TimeUnit.*;

/**
 * Created by peter.lawrey on 22/01/15.
 */
public class EventGroup implements EventLoop {
    static final long MONITOR_INTERVAL_MS = 200;
    private static final Logger LOG = LoggerFactory.getLogger(EventGroup.class);
    final EventLoop monitor = new MonitorEventLoop(this, new LightPauser(LightPauser.NO_BUSY_PERIOD, NANOSECONDS.convert(1, SECONDS)));
    @NotNull
    final VanillaEventLoop core;
    final BlockingEventLoop blocking = new BlockingEventLoop(this, "blocking-event-loop");
    @NotNull
    private final LightPauser pauser;

    public EventGroup(boolean daemon) {
        pauser = new LightPauser(
                NANOSECONDS.convert(20, Jvm.isDebug() ? MILLISECONDS : MICROSECONDS),
                NANOSECONDS.convert(200, Jvm.isDebug() ? MILLISECONDS : MICROSECONDS));
        core = new VanillaEventLoop(this, "core-event-loop",
                pauser, 1, daemon);
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    public void addHandler(@NotNull EventHandler handler) {
        HandlerPriority t1 = handler.priority();
        switch (t1 == null ? HandlerPriority.BLOCKING : t1) {
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
            default:
                throw new IllegalArgumentException("Unknown priority " + handler.priority());
        }
    }

    @Override
    public void start() {
        core.start();
        monitor.start();
        monitor.addHandler(new LoopBlockMonitor());
    }

    @Override
    public void stop() {
        monitor.stop();
        core.stop();
    }

    @Override
    public void close() throws IOException {
        stop();
        monitor.close();
        blocking.close();
        core.close();
    }

    class LoopBlockMonitor implements EventHandler {
        long lastInterval = 1;

        @Override
        public boolean action() {
            long loopStartMS = core.loopStartMS();
            if (loopStartMS <= 0 || loopStartMS == Long.MAX_VALUE)
                return false;
            long blockingTimeMS = Time.currentTimeMillis() - loopStartMS;
            long blockingInterval = blockingTimeMS / (MONITOR_INTERVAL_MS / 2);

            if (blockingInterval > lastInterval && !Jvm.IS_DEBUG && core.isAlive()) {
                core.dumpRunningState(core.name() + " thread has blocked for "
                                + blockingTimeMS + " ms.",
                        // check we are still in the loop.
                        () -> core.loopStartMS() == loopStartMS);

            } else {
                lastInterval = Math.max(1, blockingInterval);
            }
            return false;
        }
    }
}
