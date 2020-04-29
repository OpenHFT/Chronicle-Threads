package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

public interface CoreEventLoop extends EventLoop {
    Thread thread();

    long loopStartMS();

    void dumpRunningState(@NotNull final String message, @NotNull final BooleanSupplier finalCheck);
}
