package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.ObjectUtils;

@FunctionalInterface
public interface ExceptionHandlerStrategy {
    String IMPL_PROPERTY = "el.exception.handler";

    /**
     * TODO: use a builder pattern to construct EventLoops so we don't have this system property
     * @return ExceptionHandlerStrategy to use
     */
    static ExceptionHandlerStrategy strategy() {
        String className = Jvm.getProperty(IMPL_PROPERTY, LogDontRemove.class.getName());
        try {
            return ObjectUtils.newInstance(className);
        } catch (Exception e) {
            throw Jvm.rethrow(e);
        }
    }

    boolean handle(EventLoop eventLoop, EventHandler eventHandler, Throwable t);

    class LogAndRemove implements ExceptionHandlerStrategy {
        @Override
        public boolean handle(EventLoop eventLoop, EventHandler handler, Throwable t) {
            if (!(t instanceof InvalidEventHandlerException || t instanceof ClosedIllegalStateException)) {
                Jvm.warn().on(eventLoop.getClass(), "Removing " + handler.priority() + " handler " + handler + " after Exception", t);
            }
            return true;
        }
    }

    class LogDontRemove implements ExceptionHandlerStrategy {
        @Override
        public boolean handle(EventLoop eventLoop, EventHandler handler, Throwable t) {
            if (!(t instanceof InvalidEventHandlerException || t instanceof ClosedIllegalStateException)) {
                Jvm.warn().on(eventLoop.getClass(), "Exception thrown by handler " + handler, t);
                return false;
            }
            return true;
        }
    }
}
