/*
 * Copyright 2016-2020 chronicle.software
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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.ForceInline;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.util.ObjectUtils;
import net.openhft.chronicle.core.util.ThrowingCallable;
import org.jetbrains.annotations.NotNull;

import java.lang.Thread.State;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public enum Threads {
    ; // none

    private static final int MAX_DEPTH_TO_FOLLOW_DELEGATIONS = 20;
    static final Field GROUP = Jvm.getField(Thread.class, "group");
    static final long SHUTDOWN_WAIT_MILLIS = Long.getLong("SHUTDOWN_WAIT_MS", 500);
    static final ThreadLocal<List> listTL = ThreadLocal.withInitial(ArrayList::new);
    static ExecutorFactory executorFactory;

    static {
        ExecutorFactory instance = VanillaExecutorFactory.INSTANCE;
        try {
            String property = System.getProperty("threads.executor.factory");
            if (property != null)
                instance = ObjectUtils.newInstance(property);
        } catch (Exception e) {
            Jvm.warn().on(Threads.class, e);
        }
        executorFactory = instance;
    }

    public static ExecutorService acquireExecutorService(String name, int threads, boolean daemon) {
        return executorFactory.acquireExecutorService(name, threads, daemon);
    }

    public static ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon) {
        return executorFactory.acquireScheduledExecutorService(name, daemon);
    }

    public static void executorFactory(ExecutorFactory executorFactory) {
        Threads.executorFactory = executorFactory;
    }

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

    public static void shutdownDaemon(@NotNull ExecutorService service) {
        service.shutdownNow();
        try {
            boolean terminated = service.awaitTermination(10, TimeUnit.MILLISECONDS);
            if (!terminated) {
                terminated = service.awaitTermination(1, TimeUnit.SECONDS);
                if (!terminated) {
                    if (!(service instanceof ThreadPoolExecutor))
                        Jvm.warn().on(Threads.class, "*** FAILED TO TERMINATE " + service);
                    warnRunningThreads(service);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void shutdown(@NotNull ExecutorService service, boolean daemon) {
        if (daemon)
            shutdownDaemon(service);
        else
            shutdown(service);
    }

    public static void shutdown(@NotNull ExecutorService service) {

        service.shutdown();
        // without these 2 here, some threads that were in a LockSupport.parkNanos were taking a long time to shut down
        Threads.unpark(service);
        Threads.interrupt(service);
        try {

            if (!service.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                service.shutdownNow();

                if (!(service instanceof ThreadPoolExecutor)) {
                    Jvm.warn().on(Threads.class, "*** FAILED TO TERMINATE " + service);
                }
                warnRunningThreads(service);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void warnRunningThreads(@NotNull ExecutorService service) {
        Jvm.pause(100);

        forEachThread(service, t -> {
            StringBuilder b = new StringBuilder("**** THE " +
                    t.getName() +
                    " THREAD DID NOT SHUTDOWN ***\n");
            for (StackTraceElement s : t.getStackTrace())
                b.append("  ").append(s).append("\n");
            Jvm.warn().on(Threads.class, b.toString());
        });
    }

    public static void unpark(ExecutorService service) {
        forEachThread(service, LockSupport::unpark);
    }

    public static void interrupt(ExecutorService service) {
        Threads.forEachThread(service, Thread::interrupt);
    }

    static void forEachThread(ExecutorService service, Consumer<Thread> consumer) {
        try {
            final Set workers;
            if (!(service instanceof ThreadPoolExecutor)) {
                workers = Jvm.getValue(resolveDelegatedExecutorServices(service), "workers");
            } else {
                workers = Jvm.getValue(service, "workers");
            }
            if (workers == null) {
                Jvm.warn().on(Threads.class, "Couldn't find workers for " + service.getClass());
                return;
            }
            ReentrantLock mainLock = null;
            try {
                mainLock = Jvm.getValue(service, "mainLock");
            } catch (Error e) {
                Jvm.debug().on(Threads.class, e);
            }

            List objects = listTL.get();
            objects.clear();
            if (mainLock != null) mainLock.lock();
            try {
                // from ThreadPoolExecutor source docs: workers field is protected by mainLock
                objects.addAll(workers);
            } finally {
                if (mainLock != null) mainLock.unlock();
            }

            for (Object o : objects) {
                Thread t = Jvm.getValue(o, "thread");
                if (t.getState() != State.TERMINATED)
                    consumer.accept(t);
            }
        } catch (Exception e) {
            Jvm.debug().on(Threads.class, e);
        }
    }

    /**
     * Recursively resolve DelegatedExecutorServices
     *
     * @param executorService An ExecutorService
     * @return The first ExecutorService in the delegation chain that is not a DelegatedExecutorService
     */
    @NotNull
    private static ExecutorService resolveDelegatedExecutorServices(@NotNull ExecutorService executorService) {
        return resolveDelegatedExecutorServices(executorService, 0);
    }

    @NotNull
    private static ExecutorService resolveDelegatedExecutorServices(@NotNull ExecutorService executorService, int depth) {
        if (depth > MAX_DEPTH_TO_FOLLOW_DELEGATIONS) {
            Jvm.warn().on(Threads.class, "Recursion limit hit, there may be a loop");
            return executorService;
        }
        try {
            Field eField = Jvm.getFieldOrNull(executorService.getClass(), "e");
            if (eField != null) {
                Object eFieldValue = eField.get(executorService);
                if (eFieldValue instanceof ExecutorService) {
                    return resolveDelegatedExecutorServices((ExecutorService) eFieldValue, depth + 1);
                }
            }
        } catch (IllegalAccessException | IllegalArgumentException error) {
            // We can't access the field, move on
        }
        return executorService;
    }

    static void loopFinishedQuietly(EventHandler eventHandler) {
        try {
            eventHandler.loopFinished();
        } catch (Throwable t) {
            Jvm.warn().on(Threads.class, t);
        }
    }
}
