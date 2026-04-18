/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Factory for {@link EventHandler} instances that observe a {@link Pauser}.
 *
 * <p>Implementations are discovered through Java's {@link ServiceLoader}
 * mechanism.  When no implementation is found a no-op handler is returned.</p>
 */
public interface PauserMonitorFactory {

    /**
     * Create an event handler that records the behaviour of a {@code pauser}.
     * Typical implementations will log the pause count or total time paused and
     * may alert if the pauser has remained idle for longer than {@code seconds}.
     *
     * @param pauser       the {@link Pauser} to monitor
     * @param description  label used in the monitor's {@code toString}
     * @param seconds      threshold before reporting prolonged pauses
     * @return an event handler suitable for a monitoring loop
     */
    EventHandler pauserMonitor(Pauser pauser, String description, int seconds);

    static PauserMonitorFactory load() {
        final Iterator<PauserMonitorFactory> iterator = ServiceLoader.load(PauserMonitorFactory.class).iterator();
        return iterator.hasNext() ?
                iterator.next() :
                (pauser, description, seconds) -> new EventHandler() {
                    @Override
                    public boolean action() throws InvalidEventHandlerException {
                        throw new InvalidEventHandlerException();
                    }
                    @Override
                    public String toString() {
                        return "NOOP_PAUSER_MONITOR";
                    }
                };
    }
}
