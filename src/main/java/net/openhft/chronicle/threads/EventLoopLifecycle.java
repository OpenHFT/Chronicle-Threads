package net.openhft.chronicle.threads;

/**
 * The life-cycle of an event loop
 * <p>
 * possible transitions include:
 * <pre>
 *      +-------------------------------------------------+
 *      |                                                 v
 * +---------+    +-----------+    +-----------+    +-----------+
 * |   NEW   |--->|  STARTED  |--->|  STOPPING |--->|  STOPPED  |
 * +---------+    +-----------+    +-----------+    +-----------+
 * </pre>
 */
public enum EventLoopLifecycle {
    /**
     * The event loop has been created but not yet started
     */
    NEW,

    /**
     * The event loop has been started but not yet stopped
     */
    STARTED,

    /**
     * Stop has been called, but some handlers are yet to complete
     */
    STOPPING,

    /**
     * The event loop has been stopped
     */
    STOPPED
}
