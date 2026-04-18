/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/**
 * Contract for the fast core loop used within an {@link EventGroup}.
 *
 * <p>The core loop runs on a dedicated thread and executes handlers
 * one by one. Implementations aim to minimise latency and usually rely
 * on a {@link net.openhft.chronicle.threads.Pauser} during idle periods.
 */
public interface CoreEventLoop extends EventLoop {

    /**
     * The value returned for {@link #loopStartNS()} when the event loop is not currently
     * executing an iteration
     */
    long NOT_IN_A_LOOP = Long.MAX_VALUE;

    /**
     * The thread currently running the loop.
     *
     * @return the loop thread, or {@code null} if the loop has not yet started
     * or has finished
     */
    Thread thread();

    /**
     * Time in {@link System#nanoTime()} units when the current iteration began.
     *
     * @return the start time, or {@link #NOT_IN_A_LOOP} if the loop is idle
     */
    long loopStartNS();

    /**
     * Dump the stack trace when a monitor suspects the loop is blocked.
     *
     * @param message    text to include in the log
     * @param finalCheck invoked after taking the stack trace; the state is
     *                   logged only when this returns {@code true}
     */
    void dumpRunningState(@NotNull String message, @NotNull BooleanSupplier finalCheck);

    /**
     * Check whether the given thread is executing this loop.
     *
     * <p>Used by diagnostics to ignore activity from other threads.</p>
     *
     * @param thread candidate thread
     * @return {@code true} if the loop is running on {@code thread}
     */
    boolean isRunningOnThread(Thread thread);

    void privateGroup(boolean privateGroup);
}
