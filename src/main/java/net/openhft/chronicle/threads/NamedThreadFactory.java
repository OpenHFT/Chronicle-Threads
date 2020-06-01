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

import net.openhft.chronicle.core.util.WeakIdentityHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.newSetFromMap;
import static java.util.Collections.synchronizedSet;

public class NamedThreadFactory implements ThreadFactory {
    private final AtomicInteger id = new AtomicInteger();
    private final String name;
    private final Boolean daemon;
    private final Integer priority;
    private Set<Thread> threads = synchronizedSet(newSetFromMap(new WeakIdentityHashMap<>()));

    public NamedThreadFactory(String name) {
        this(name, null);
    }

    public NamedThreadFactory(String name, Boolean daemon) {
        this(name, daemon, null);
    }

    public NamedThreadFactory(String name, Boolean daemon, Integer priority) {
        this.name = name;
        this.daemon = daemon;
        this.priority = priority;
    }

    @Override
    @NotNull
    public Thread newThread(@NotNull Runnable r) {
        int id = this.id.getAndIncrement();
        String nameN = Threads.threadGroupPrefix() + (id == 0 ? this.name : (this.name + '-' + id));
        Thread t = new Thread(r, nameN);
        threads.add(t);
        if (daemon != null)
            t.setDaemon(daemon);
        if (priority != null)
            t.setPriority(priority);
        return t;
    }

    public Set<Thread> threads() {
        return threads;
    }
}
