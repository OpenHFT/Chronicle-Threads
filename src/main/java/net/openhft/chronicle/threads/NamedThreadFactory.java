/*
 * Copyright 2016-2020 chronicle.software
 *
 *       https://chronicle.software
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
 * A {@code NamedThreadFactory} that creates threads with specific names, daemon status, and priorities.
 * This factory also optionally supports event loop integration and records the stack trace location where
 * each factory instance is created, useful for tracking and debugging resources.
 */
public class NamedThreadFactory extends ThreadGroup implements ThreadFactory {
    private final AtomicInteger id = new AtomicInteger();  // Counter for unique thread IDs
    private final String nameShadow;  // Base name for threads created by this factory
    private final Boolean daemonShadow;  // Determines if threads should be daemon threads
    private final Integer priority;  // Priority assigned to created threads
    private final StackTrace createdHere;  // Stack trace marking the factory creation location
    private final boolean inEventLoop;  // Flag indicating if threads belong to an event loop

    /**
     * Constructs a {@code NamedThreadFactory} with a specified name.
     *
     * @param name the base name for threads created by this factory
     */
    public NamedThreadFactory(String name) {
        this(name, null, null);
    }

    /**
     * Constructs a {@code NamedThreadFactory} with a specified name and daemon status.
     *
     * @param name   the base name for threads created by this factory
     * @param daemon {@code true} if created threads should be daemon threads, {@code false} otherwise
     */
    public NamedThreadFactory(String name, Boolean daemon) {
        this(name, daemon, null);
    }

    /**
     * Constructs a {@code NamedThreadFactory} with a specified name, daemon status, and priority.
     *
     * @param name     the base name for threads created by this factory
     * @param daemon   {@code true} if created threads should be daemon threads, {@code false} otherwise
     * @param priority the priority level for threads created by this factory
     */
    public NamedThreadFactory(String name, Boolean daemon, Integer priority) {
        this(name, daemon, priority, false);
    }

    /**
     * Constructs a {@code NamedThreadFactory} with a specified name, daemon status, priority, and event loop flag.
     *
     * @param name        the base name for threads created by this factory
     * @param daemon      {@code true} if created threads should be daemon threads, {@code false} otherwise
     * @param priority    the priority level for threads created by this factory
     * @param inEventLoop {@code true} if threads belong to an event loop, {@code false} otherwise
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
     * Creates a new {@link Thread} configured with the specified name, daemon status, and priority.
     *
     * @param r the {@link Runnable} to associate with the new thread
     * @return the newly created {@link Thread}
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
     * Interrupts all active threads created by this factory.
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
