/*
 * Copyright 2016-2025 chronicle.software
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
import net.openhft.chronicle.core.StackTrace;
import net.openhft.chronicle.core.threads.CleaningThread;
import net.openhft.chronicle.core.threads.ThreadDump;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates named threads within a dedicated group.  The first thread is named
 * {@code groupName} and each subsequent one is suffixed with {@code -n} where
 * {@code n} increments from one.  Every thread is a {@link CleaningThread} so
 * that thread-local resources are cleared when it terminates.
 */
public class NamedThreadFactory extends ThreadGroup implements ThreadFactory {
    private final AtomicInteger id = new AtomicInteger();
    private final String nameShadow;
    private final Boolean daemonShadow;
    private final Integer priority;
    private final StackTrace createdHere;
    private final boolean inEventLoop;

    public NamedThreadFactory(String name) {
        this(name, null, null);
    }

    public NamedThreadFactory(String name, Boolean daemon) {
        this(name, daemon, null);
    }

    public NamedThreadFactory(String name, Boolean daemon, Integer priority) {
        this(name, daemon, priority, false);
    }

    /**
     * Constructs a factory with the supplied options.
     *
     * @param name        prefix used for the thread group and thread names
     * @param daemon      set to {@code true} if created threads should be daemons
     * @param priority    priority to assign or {@code null} for the JVM default
     * @param inEventLoop mark threads as part of an event loop for monitoring
     */
    public NamedThreadFactory(String name, Boolean daemon, Integer priority, boolean inEventLoop) {
        super(name);
        this.nameShadow = name;
        this.daemonShadow = daemon;
        this.priority = priority;
        this.inEventLoop = inEventLoop;
        createdHere = Jvm.isResourceTracing() ? new StackTrace("NamedThreadFactory created here") : null;
    }

    /**
     * Returns a new {@link CleaningThread} executing the given task.  The
     * thread name is formed by {@link Threads#threadGroupPrefix()} followed by
     * the factory name.  Subsequent threads append {@code -n} where {@code n}
     * is an incrementing number.
     */
    @Override
    @NotNull
    public Thread newThread(@NotNull Runnable r) {
        final int idSnapshot = this.id.getAndIncrement();
        final String nameN = Threads.threadGroupPrefix() + (idSnapshot == 0 ? this.nameShadow : (this.nameShadow + '-' + idSnapshot));
        Thread t = new CleaningThread(r, nameN, inEventLoop);
        ThreadDump.add(t, createdHere);
        if (daemonShadow != null)
            t.setDaemon(daemonShadow);
        if (priority != null)
            t.setPriority(priority);
        return t;
    }

    /**
     * Interrupts every thread currently in this group.  Threads that have
     * already finished are ignored.
     */
    public void interruptAll() {
        Thread[] list = new Thread[activeCount() + 1];
        super.enumerate(list);
        for (Thread thread : list) {
            if (thread != null)
                thread.interrupt();
        }
    }
}
