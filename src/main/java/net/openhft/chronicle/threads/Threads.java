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
import net.openhft.chronicle.core.annotation.ForceInline;
import net.openhft.chronicle.core.util.ThrowingCallable;
import org.jetbrains.annotations.NotNull;

import java.lang.Thread.State;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/*
 * Created by Peter Lawrey on 24/06/15.
 */
public enum Threads {
    ;

    static final Field GROUP = Jvm.getField(Thread.class, "group");

    @ForceInline
    public static <R, T extends Throwable> R withThreadGroup(ThreadGroup tg, @NotNull ThrowingCallable<R, T> callable) throws T {
        Thread thread = Thread.currentThread();
        ThreadGroup tg0 = thread.getThreadGroup();
        setThreadGroup(thread, tg);
        try {
            return callable.call();

        } finally {
            setThreadGroup(thread, tg0);
        }
    }

    @ForceInline
    public static void setThreadGroup(Thread thread, ThreadGroup tg) {
        try {
            GROUP.set(thread, tg);

        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @NotNull
    public static String threadGroupPrefix() {
        String threadGroupName = Thread.currentThread().getThreadGroup().getName();
        if (!threadGroupName.endsWith("/"))
            threadGroupName += "/";
        return threadGroupName;
    }

    public static void shutdown(@NotNull ExecutorService service) {
        service.shutdown();
        try {

            if (!service.awaitTermination(1, TimeUnit.SECONDS)) {
                    service.shutdownNow();

                    try {
                        if (!service.awaitTermination(20, TimeUnit.SECONDS)) {
                            if (service instanceof ThreadPoolExecutor) {
                                try {
                                    Field workers = ThreadPoolExecutor.class.getDeclaredField("workers");
                                    workers.setAccessible(true);
                                    Set objects = (Set) workers.get(service);
                                    for (Object o : objects) {
                                        Field thread = o.getClass().getDeclaredField("thread");
                                        thread.setAccessible(true);
                                        Thread t = (Thread) thread.get(o);
                                        if (t.getState() != State.TERMINATED) {

                                            StringBuilder b = new StringBuilder("**** THE " +
                                                    "FOLLOWING " +
                                                    "THREAD DID NOT SHUTDOWN ***\n");
                                            for (StackTraceElement s : t.getStackTrace()) {
                                                b.append("  "+s.toString() + "\n");
                                            }
                                            Jvm.warn().on(Threads.class, b.toString());
                                        }
                                    }

                                } catch (Exception e) {
                                    Jvm.warn().on(Threads.class, e);
                                }

                            } else
                                service.shutdownNow();

                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
