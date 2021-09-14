package net.openhft.chronicle.threads;


import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;

/**
 * EventLoop.loopStarted needs to be called once before the first iteration of an
 * EventHandler and it must be called on the event loop thread. An easy way to achieve
 * that is to wrap the handler in this idempotent decorator and call it at the start
 * of every loop.
 */
public class IdempotentLoopStartedEventHandler extends AbstractCloseable implements EventHandler {

    private final EventHandler eventHandler;
    private boolean loopStarted = false;

    public IdempotentLoopStartedEventHandler(@NotNull EventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    @Override
    public boolean action() throws InvalidEventHandlerException {
        return eventHandler.action();
    }

    @Override
    public void eventLoop(EventLoop eventLoop) {
        eventHandler.eventLoop(eventLoop);
    }

    @Override
    public void loopStarted() {
        if (!loopStarted) {
            loopStarted = true;
            eventHandler.loopStarted();
        }
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
    public boolean equals(Object o) {
        return eventHandler.equals(o);
    }

    @Override
    public int hashCode() {
        return eventHandler.hashCode();
    }

    @Override
    protected void performClose() throws IllegalStateException {
        Closeable.closeQuietly(eventHandler);
    }
}
