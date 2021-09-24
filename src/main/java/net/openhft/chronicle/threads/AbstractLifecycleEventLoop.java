package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A parent class that:
 * <ul>
 *     <li>Enforces the life-cycle of an EventLoop</li>
 *     <li>Implements idempotency for {@link #start()}, {@link #stop()}</li>
 *     <li>Ensures {@link #stop()} only returns when the EventLoop is stopped</li>
 * </ul>
 * See {@link EventLoopLifecycle} for details of the life-cycle
 */
public abstract class AbstractLifecycleEventLoop extends AbstractCloseable implements EventLoop {

    protected final String name;
    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);

    protected AbstractLifecycleEventLoop(@NotNull String name) {
        this.name = name;
        disableThreadSafetyCheck(true);
    }

    @Override
    public final void start() {
        throwExceptionIfClosed();

        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STARTED)) {
            performStart();
        }
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * Implement whatever this event loop needs to start, will only
     * ever be called once
     */
    protected abstract void performStart();

    @Override
    public final void stop() {
        close();
    }

    protected void performClose() {
        setAsStopping();
        awaitTermination(true);
    }

    protected void setAsStopping() {
        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STOPPING)) {
            performStopFromNew();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else if (lifecycle.compareAndSet(EventLoopLifecycle.STARTED, EventLoopLifecycle.STOPPING)) {
            performStopFromStarted();
        }
    }

    protected void postClose() {
        awaitTermination(true);
    }

    /**
     * Called when the event loop run() finishes.
     */
    protected void setAsStopped() {
        lifecycle.set(EventLoopLifecycle.STOPPED);
    }

    /**
     * Implement a stop from {@link EventLoopLifecycle#NEW} state, should block until all
     * handlers have had {@link EventHandler#loopFinished()} called.
     */
    protected abstract void performStopFromNew();

    /**
     * Implement a stop from {@link EventLoopLifecycle#STARTED} state, should block until all
     * handlers have completed their final iteration and had
     * {@link EventHandler#loopFinished()} called.
     */
    protected abstract void performStopFromStarted();

    @Override
    public final void awaitTermination() {
        awaitTermination(false);
    }

    protected final void awaitTermination(boolean expectStopping) {
        if (Thread.currentThread().getName().equals("Finalizer"))
            return;

        long start = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()) {
            final EventLoopLifecycle lifecycle = this.lifecycle.get();
            switch (lifecycle) {
                case NEW:
                case STARTED:
                default:
                    if (expectStopping && this.lifecycle.compareAndSet(lifecycle, EventLoopLifecycle.STOPPING))
                        Jvm.warn().on(getClass(), "Lifecycle was reset to " + lifecycle + ", stopping");
                    break;
                case STOPPING:
                    break;
                case STOPPED:
                    return;
            }
            if (System.currentTimeMillis() > start + 120_000)
                throw new IllegalStateException(this + " still running after 120 seconds");
            Jvm.pause(1);
            if (!isAlive())
                setAsStopped();
        }
        if (lifecycle.get() != EventLoopLifecycle.STOPPED) {
            Jvm.warn().on(getClass(), "awaitTermination() interrupted, returning in state " + lifecycle.get());
        }
    }

    protected boolean isStarted() {
        return lifecycle.get() == EventLoopLifecycle.STARTED;
    }

    @Override
    public boolean isStopped() {
        return lifecycle.get() == EventLoopLifecycle.STOPPED;
    }
}
