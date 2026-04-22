/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.util.ObjectUtils;
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

/**
 * Miscellaneous helper methods for thread and executor management.
 *
 * <p>The enum acts as a static holder and exposes utilities to acquire and
 * shut down executor services, inspect running threads and assist with
 * event loop execution.</p>
 */
public enum Threads {
    ; // none

    private static final int MAX_DEPTH_TO_FOLLOW_DELEGATIONS = 20;
    static final long SHUTDOWN_WAIT_MILLIS = Jvm.getLong("SHUTDOWN_WAIT_MS", 500L);
    static final ThreadLocal<List<Object>> listTL = ThreadLocal.withInitial(ArrayList::new);
    static ExecutorFactory executorFactory;

    static {
        ExecutorFactory instance = VanillaExecutorFactory.INSTANCE;
        try {
            String property = Jvm.getProperty("threads.executor.factory");
            if (property != null)
                // CSResolvedTypeInstantiation REVIEW keep ObjectUtils.newInstance here because this type-materialization path still needs an explicit reviewed type-resolution contract.
                instance = ObjectUtils.newInstance(property);
                // CSCatchBroadException REVIEW catch (Exception e) because the local fallback still begins with logging or printing a diagnostic and needs either narrower handling or an explicit reviewed recovery contract.
        } catch (Exception e) {
            Jvm.warn().on(Threads.class, e);
        }
        executorFactory = instance;
    }

    /**
     * Acquire an executor service from the current factory.
     *
     * @param name    prefix used when naming threads
     * @param threads number of worker threads
     * @param daemon  whether the threads should be daemons
     * @return the executor service to use
     */
    public static ExecutorService acquireExecutorService(String name, int threads, boolean daemon) {
        return executorFactory.acquireExecutorService(name, threads, daemon);
    }

    /**
     * Acquire a scheduled executor service from the current factory.
     *
     * @param name   prefix used when naming threads
     * @param daemon whether the threads should be daemons
     * @return the scheduled executor service
     */
    public static ScheduledExecutorService acquireScheduledExecutorService(String name, boolean daemon) {
        return executorFactory.acquireScheduledExecutorService(name, daemon);
    }

    /**
     * Install an alternative factory used to create executors.
     *
     * @param executorFactory provider used henceforth
     */
    public static void executorFactory(ExecutorFactory executorFactory) {
        Threads.executorFactory = executorFactory;
    }

    /**
     * Return the current thread group name with a trailing slash.
     *
     * @return thread group prefix
     */
    @NotNull
    public static String threadGroupPrefix() {
        String threadGroupName = Thread.currentThread().getThreadGroup().getName();
        if (!threadGroupName.endsWith("/"))
            threadGroupName += "/";
        return threadGroupName;
    }

    /**
     * Shutdown a daemon {@link ExecutorService}. We stop the service immediately as we want to
     * stop whatever is executing quickly
     *
     * @param service service
     */
    public static void shutdownDaemon(@NotNull ExecutorService service) {
        // don't change this to shutdown() as it will cause test failures - allowing daemon services
        // to stop politely gives us more races e.g. you may see things that are shutting down re-connecting
        // CSShutdownNowUse REVIEW keep service.shutdownNow here because this lifecycle or ownership exception in Threads#shutdownDaemon still needs an explicit reviewed lifecycle contract.
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

    /**
     * Shutdown the service according to the daemon flag.
     *
     * @param service the executor to close
     * @param daemon  invoke {@link #shutdownDaemon(ExecutorService)} when true
     */
    public static void shutdown(@NotNull ExecutorService service, boolean daemon) {
        if (daemon)
            shutdownDaemon(service);
        else
            shutdown(service);
    }

    /**
     * Shutdown a {@link ExecutorService}. We assume that the service's tasks have already been told to
     * stop (e.g. {@code running.set(false)}) and that we can initially just wait (for {@link #SHUTDOWN_WAIT_MILLIS})
     * for the service to complete. If it does not stop by itself then we terminate it.
     *
     * @param service service
     */
    public static void shutdown(@NotNull ExecutorService service) {

        service.shutdown();
        // without this here, some threads that were in a LockSupport.parkNanos were taking a long time to shut down
        Threads.unpark(service);
        try {

            if (!service.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                // CSShutdownNowUse REVIEW keep service.shutdownNow here because this lifecycle or ownership exception in Threads#shutdown still needs an explicit reviewed lifecycle contract.
                service.shutdownNow();

                if (!service.awaitTermination(10, TimeUnit.MILLISECONDS)) {
                    if (!(service instanceof ThreadPoolExecutor)) {
                        Jvm.warn().on(Threads.class, "*** FAILED TO TERMINATE " + service);
                    }
                    warnRunningThreads(service);
                }
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
            renderStackTrace(b, t.getStackTrace());
            Jvm.warn().on(Threads.class, b.toString());
        });
    }

    /**
     * Render a stack trace
     *
     * @param stringBuilder      The string builder to render to
     * @param stackTraceElements The array of stack-trace elements
     */
    public static void renderStackTrace(StringBuilder stringBuilder, StackTraceElement[] stackTraceElements) {
        for (StackTraceElement s : stackTraceElements)
            stringBuilder.append("  ").append(s).append("\n");
    }

    /**
     * Unpark all threads belonging to the service.
     *
     * @param service executor whose threads should be unparked
     */
    public static void unpark(ExecutorService service) {
        forEachThread(service, LockSupport::unpark);
    }

    /**
     * Interrupt all threads managed by the service.
     *
     * @param service executor whose threads should be interrupted
     */
    public static void interrupt(ExecutorService service) {
        Threads.forEachThread(service, Thread::interrupt);
    }

    static void forEachThread(ExecutorService service, Consumer<Thread> consumer) {
        try {
            if (!(service instanceof ThreadPoolExecutor))
                service = resolveDelegatedExecutorServices(service);
            if (!(service instanceof ThreadPoolExecutor))
                return;
            // CSReflectiveFieldLookup REVIEW final Set<Object> workers = Jvm.getValue(service, "workers") because this reflective or runtime-loading boundary in Threads#forEachThread still needs either an allowlisted wrapper or an explicit reviewed runtime-loading contract.
            final Set<Object> workers = Jvm.getValue(service, "workers");
            if (workers == null) {
                Jvm.warn().on(Threads.class, "Couldn't find workers for " + service.getClass());
                return;
            }
            ReentrantLock mainLock = null;
            try {
                // CSReflectiveFieldLookup REVIEW mainLock = Jvm.getValue(service, "mainLock") because this reflective or runtime-loading boundary in Threads#forEachThread still needs either an allowlisted wrapper or an explicit reviewed runtime-loading contract.
                mainLock = Jvm.getValue(service, "mainLock");
                // CSWarnAndContinue REVIEW catch (Error e) because the local fallback still begins with executing Jvm.debug().on(Threads.class, e) and then continues execution, and needs either fail-closed handling or an explicit reviewed degraded-mode contract.
            } catch (Error e) {
                Jvm.debug().on(Threads.class, e);
            }

            List<Object> objects = listTL.get();
            objects.clear();

            if (mainLock != null) {
                try {
                    mainLock.lock();
                    // from ThreadPoolExecutor source docs: workers field is protected by mainLock
                    objects.addAll(workers);
                } finally {
                    mainLock.unlock();
                }
            } else {
                objects.addAll(workers);
            }

            for (Object o : objects) {
                // CSReflectiveFieldLookup REVIEW Thread t = Jvm.getValue(o, "thread") because this reflective or runtime-loading boundary in Threads#forEachThread still needs either an allowlisted wrapper or an explicit reviewed runtime-loading contract.
                Thread t = Jvm.getValue(o, "thread");
                if (t.getState() != State.TERMINATED)
                    consumer.accept(t);
            }
            // CSCatchBroadException REVIEW catch (Exception e) because the local fallback still begins with executing Jvm.debug().on(Threads.class, e) and needs either narrower handling or an explicit reviewed recovery contract.
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
            // CSReflectiveFieldLookup REVIEW Field eField = Jvm.getFieldOrNull(executorService.getClass(), "e") because this reflective or runtime-loading boundary in Threads#resolveDelegatedExecutorServices still needs either an allowlisted wrapper or an explicit reviewed runtime-loading contract.
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

    static void eventLoopQuietly(EventLoop eventLoop, @NotNull EventHandler handler) {
        try {
            handler.eventLoop(eventLoop);
            // CSCatchThrowable REVIEW catch (Throwable t) because the local fallback still begins with logging or printing a diagnostic and needs either a narrower terminal boundary or an explicit reviewed last-resort contract.
        } catch (Throwable t) {
            Jvm.warn().on(eventLoop.getClass(), "EventHandler::eventLoop exception", t);
        }
    }

    static boolean loopStartedCall(EventLoop eventLoop, @NotNull EventHandler handler) {
        try {
            handler.loopStarted();
            return false;
            // CSCatchThrowable REVIEW catch (Throwable t) because the local fallback still begins with logging or printing a diagnostic and needs either a narrower terminal boundary or an explicit reviewed last-resort contract.
        } catch (Throwable t) {
            Jvm.warn().on(eventLoop.getClass(), "EventHandler::loopStarted exception. Removing handler", t);
            return true;
        }
    }

    static void loopFinishedQuietly(EventHandler eventHandler) {
        try {
            eventHandler.loopFinished();
            // CSCatchThrowable REVIEW catch (Throwable t) because the local fallback still begins with logging or printing a diagnostic and needs either a narrower terminal boundary or an explicit reviewed last-resort contract.
        } catch (Throwable t) {
            Jvm.warn().on(Threads.class, t);
        }
    }
}
