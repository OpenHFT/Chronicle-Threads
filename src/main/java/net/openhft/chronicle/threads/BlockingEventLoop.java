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
import net.openhft.chronicle.threads.api.EventHandler;
import net.openhft.chronicle.threads.api.EventLoop;
import net.openhft.chronicle.threads.api.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * Event "Loop" for blocking tasks. Created by peter.lawrey on 26/01/15.
 */
public class BlockingEventLoop implements EventLoop {

    private static final Logger LOG = LoggerFactory.getLogger(BlockingEventLoop.class);

    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    @Nullable
    private Thread thread = null;
    private volatile boolean closed;
    private EventHandler handler;

    public BlockingEventLoop(EventLoop parent, String name) {
        this.parent = parent;
        service = Executors.newCachedThreadPool(new NamedThreadFactory(name, true));
    }

    @Override
    public void addHandler(@NotNull EventHandler handler) {
        this.handler = handler;
        try {
            service.submit(() -> {
                thread = Thread.currentThread();
                handler.eventLoop(parent);
                try {
                    while (!closed)
                        handler.action();

                } catch (InvalidEventHandlerException e) {
                    // expected
                } catch (Throwable t) {
                    LOG.error("", t);
                } finally {
                    if (LOG.isDebugEnabled())
                        LOG.debug("handler " + handler + " done.");
                    if (closed)
                        closeQuietly(this.handler);
                }
            });
        } catch (RejectedExecutionException e) {
            if (!closed)
                LOG.error("", e);
        }
    }

    @Override
    public void start() {
    }

    @Override
    public void unpause() {

    }

    @Override
    public void stop() {
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(this.handler);
        service.shutdown();

        try {
            if (!(service.awaitTermination(500, TimeUnit.MILLISECONDS)))
                service.shutdownNow();
        } catch (InterruptedException e) {
            service.shutdownNow();
        }


        try {
            if (thread != null)
                thread.join(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread != null && thread.isAlive()) {
            StackTraceElement[] stackTrace = thread.getStackTrace();
            StringBuilder sb = new StringBuilder(thread + " still running ");
            Jvm.trimStackTrace(sb, stackTrace);
            LOG.info(sb.toString());
        }
    }
}
