/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

/**
 * The life-cycle of an event loop. The state moves from {@link #NEW} to
 * {@link #STARTED} when {@code start()} is invoked. A request to {@code stop()}
 * moves the loop to {@link #STOPPING} and once all handlers have completed it
 * becomes {@link #STOPPED}.
 * <p>
 * Possible transitions include:
 * <pre>
 *      +-------------------------------------------------+
 *      |                                                 v
 * +---------+    +-----------+    +-----------+    +-----------+
 * |   NEW   |---&gt;|  STARTED  |---&gt;|  STOPPING |---&gt;|  STOPPED  |
 * +---------+    +-----------+    +-----------+    +-----------+
 * </pre>
 */
public enum EventLoopLifecycle {
    /**
     * The event loop has been created but not yet started. Only
     * {@code start()} or {@code stop()} are meaningful in this state.
     */
    NEW(false),

    /**
     * The event loop is running. Calling {@code stop()} moves it to
     * {@link #STOPPING}.
     */
    STARTED(false),

    /**
     * {@code stop()} has been called and handlers are finishing. Further calls
     * to {@code stop()} wait for completion.
     */
    STOPPING(true),

    /**
     * The event loop has been stopped and cannot be restarted.
     */
    STOPPED(true);

    private final boolean stopped;

    EventLoopLifecycle(boolean stopped) {
        this.stopped = stopped;
    }

    /**
     * Indicates whether the lifecycle is in a terminal stopped state.
     *
     * @return {@code true} when no further work should run
     */
    public boolean isStopped() {
        return stopped;
    }
}
