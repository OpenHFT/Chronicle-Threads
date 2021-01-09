package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

@Deprecated(/* To be removed in x.23 */)
public class DelegateEventHandler implements EventHandler {
    private final EventHandler eventHandler;

    protected DelegateEventHandler(EventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    @Override
    public void eventLoop(EventLoop eventLoop) {
        eventHandler.eventLoop(eventLoop);
    }

    @Override
    public void loopStarted() {
        eventHandler.loopStarted();
    }

    @Override
    public void loopFinished() {
        eventHandler.loopFinished();
    }

    @Override
    public @NotNull HandlerPriority priority() {
        return eventHandler.priority();
    }

    @Override
    public boolean action() throws InvalidEventHandlerException {
        return eventHandler.action();
    }
}
