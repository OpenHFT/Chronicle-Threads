package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.jetbrains.annotations.NotNull;

public interface ThreadMonitor extends EventHandler {
    @Override
    default @NotNull HandlerPriority priority() {
        return HandlerPriority.MONITOR;
    }
}
