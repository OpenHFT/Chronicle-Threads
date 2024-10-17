package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

import java.util.Iterator;
import java.util.ServiceLoader;

public interface PauserMonitorFactory {
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
