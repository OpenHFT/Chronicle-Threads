/*
 *
 *  *     Copyright (C) ${YEAR}  higherfrequencytrading.com
 *  *
 *  *     This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU Lesser General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU Lesser General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU Lesser General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
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
