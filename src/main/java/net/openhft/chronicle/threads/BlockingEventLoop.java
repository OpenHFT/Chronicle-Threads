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
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * Event "Loop" for blocking tasks. Created by peter.lawrey on 26/01/15.
 */
public class BlockingEventLoop implements EventLoop {
    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    private Thread thread = null;
    private volatile boolean closed;
    private EventHandler handler;

    public BlockingEventLoop(EventLoop parent, String name) {
        this.parent = parent;
        service = Executors.newCachedThreadPool(new NamedThreadFactory(name, true));
    }

    @Override
    public void addHandler(@NotNull EventHandler handler) {
        closeQuietly(this.handler);
        this.handler = handler;
        service.submit(() -> {
            thread = Thread.currentThread();
            handler.eventLoop(parent);
            while (!closed && !handler.isDead())
                handler.action();
        });
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
        service.shutdownNow();
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
            System.out.println(sb);
        }
    }
}
