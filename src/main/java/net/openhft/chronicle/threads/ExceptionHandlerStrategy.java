package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.ObjectUtils;

/**
 * Strategy for how to deal with exceptions in handlers
 */
@FunctionalInterface
public interface ExceptionHandlerStrategy {
    @Deprecated(/* Remove in .25 */)
    String IMPL_PROPERTY = "el.exception.handler";

    /**
     * @deprecated Use {@link #EventGroup.builder()} - to be removed in .25
     * @return ExceptionHandlerStrategy to use
     */
    @Deprecated(/* Remove in .25 */)
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
            if (!(t instanceof InvalidEventHandlerException)) {
                Jvm.warn().on(eventLoop.getClass(), "Removing " + handler.priority() + " handler " + handler + " after Exception", t);
            }
            return true;
        }
    }

    class LogDontRemove implements ExceptionHandlerStrategy {
        @Override
        public boolean handle(EventLoop eventLoop, EventHandler handler, Throwable t) {
            if (!(t instanceof InvalidEventHandlerException)) {
                Jvm.warn().on(eventLoop.getClass(), "Exception thrown by handler " + handler, t);
                return false;
            }
            return true;
        }
    }
}
