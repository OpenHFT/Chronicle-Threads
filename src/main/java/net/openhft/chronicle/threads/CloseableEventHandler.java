package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

/**
 * Created by peter on 14/03/16.
 */
public class CloseableEventHandler implements EventHandler, Closeable {
    private final EventHandler handler;
    private volatile boolean closed;

    public CloseableEventHandler(EventHandler handler) {
        this.handler = handler;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean action() throws InvalidEventHandlerException, InterruptedException {
        if (closed) throw new InvalidEventHandlerException();
        return handler.action();
    }

    @Override
    public String toString() {
        return "CloseableEventHandler{" +
                "handler=" + handler +
                '}';
    }
}
