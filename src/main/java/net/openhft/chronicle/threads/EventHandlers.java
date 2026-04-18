/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;

/**
 * Placeholder enum that holds simple {@link EventHandler} constants.
 * The only entry is {@link #NOOP}, whose {@code action()} method always
 * returns {@code false}.
 */
enum EventHandlers implements EventHandler {
    NOOP {
        @Override
        public boolean action() {
            return false;
        }
    }
}
