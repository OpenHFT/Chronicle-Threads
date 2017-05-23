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

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by peter on 24/06/15.
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

    static void shutdown(@NotNull ExecutorService service) {
        service.shutdown();

        try {
            service.awaitTermination(500, TimeUnit.MILLISECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } finally {
            service.shutdownNow();
        }

        try {
            service.awaitTermination(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
