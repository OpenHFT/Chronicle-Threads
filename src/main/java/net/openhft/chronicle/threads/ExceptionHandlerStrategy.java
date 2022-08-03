/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.ObjectUtils;

/**
 * Strategy for how to deal with exceptions in handlers
 * @deprecated in future, the default behaviour will be the only supported behaviour
 */
@Deprecated(/* Remove in .25 */)
@FunctionalInterface
public interface ExceptionHandlerStrategy {
    String IMPL_PROPERTY = "el.exception.handler";

    /**
     * @return ExceptionHandlerStrategy to use
     */
    static ExceptionHandlerStrategy strategy() {
        String className = Jvm.getProperty(IMPL_PROPERTY);
        if (className != null)
            Jvm.warn().on(ExceptionHandlerStrategy.class, IMPL_PROPERTY + " has been deprecated with no replacement");
        if (className == null)
            className = LogDontRemove.class.getName();
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
