/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
