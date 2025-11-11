/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;

/**
 * Configuration values for event loop behaviour.
 *
 * <p>The {@code ACCEPT_HANDLER_MOD_COUNT} system property specifies how often
 * new accept handlers are inserted to avoid starvation. A value of zero
 * disables this feature. If the property is absent the
 * {@link #DEFAULT_ACCEPT_HANDLER_MOD_COUNT default} is used. The
 * {@link #IS_ACCEPT_HANDLER_MOD_COUNT} flag reveals whether re-arming is
 * enabled.</p>
 */
public enum EventLoopUtil {
    ; // none

    /** Fallback when {@code eventloop.accept.mod} is not set. */
    private static final int DEFAULT_ACCEPT_HANDLER_MOD_COUNT = 128;

    /** Interval for re-adding accept handlers. */
    public static final int ACCEPT_HANDLER_MOD_COUNT =
            Jvm.getInteger("eventloop.accept.mod", DEFAULT_ACCEPT_HANDLER_MOD_COUNT);

    /** True when accept handler re-arming is active. */
    public static final boolean IS_ACCEPT_HANDLER_MOD_COUNT = ACCEPT_HANDLER_MOD_COUNT > 0;
}
