package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;

/**
 * Created by peter on 03/03/2016.
 */
public abstract class TimedEventHandler implements EventHandler {
    private long nextRunNS = 0;

    @Override
    public boolean action() throws InvalidEventHandlerException, InterruptedException {
        long now = System.nanoTime();
        if (nextRunNS <= now) {
            long delayUS = timedAction();
            if (delayUS < 0)
                return true;
            nextRunNS = now + delayUS * 1000;
        }
        return false;
    }

    /**
     * Perform an action
     *
     * @return the delay in micro-seconds.
     */
    protected abstract long timedAction();

    @Override
    public HandlerPriority priority() {
        return HandlerPriority.TIMER;
    }
}
