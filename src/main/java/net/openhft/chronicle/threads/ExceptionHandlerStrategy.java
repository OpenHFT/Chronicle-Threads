package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

@FunctionalInterface
public interface ExceptionHandlerStrategy {
    ExceptionHandlerStrategy LOG_AND_REMOVE = (eventLoop, handler, t) -> {
        if (!(t instanceof InvalidEventHandlerException)) {
            Jvm.warn().on(eventLoop.getClass(), "Removing " + handler.priority() + " handler " + handler + " after Exception", t);
        }
        return true;
    };
    ExceptionHandlerStrategy LOG_DONT_REMOVE = (eventLoop, handler, t) -> {
        if (!(t instanceof InvalidEventHandlerException)) {
            Jvm.warn().on(eventLoop.getClass(), "Exception thrown by handler " + handler, t);
            return false;
        }
        return true;
    };

    /**
     * TODO: use a builder pattern to construct EventLoops so we don't have this system property
     * @return ExceptionHandlerStrategy to use
     */
    static ExceptionHandlerStrategy strategy() {
        return Jvm.getBoolean("el.remove.on.exception", false) ? LOG_AND_REMOVE : LOG_DONT_REMOVE;
    }

    boolean handle(EventLoop eventLoop, EventHandler eventHandler, Throwable t);
}
