/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.jetbrains.annotations.NotNull;

/**
 * Event handler used by the monitor loop to detect threads that appear to be
 * blocked. Instances are typically produced by {@link ThreadMonitors}.
 */
public interface ThreadMonitor extends EventHandler {
    /**
     * Returns {@link HandlerPriority#MONITOR} so monitoring does not compete
     * with application handlers.
     */
    @Override
    default @NotNull HandlerPriority priority() {
        return HandlerPriority.MONITOR;
    }
}
