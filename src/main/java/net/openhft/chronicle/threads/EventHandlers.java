package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;

enum EventHandlers implements EventHandler {
    NOOP {
        @Override
        public boolean action() {
            return false;
        }
    }
}
