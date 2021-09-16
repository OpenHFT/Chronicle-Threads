package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventLoop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

public class EventLoops {

    /**
     * Stop many {@link EventLoop}s concurrently using {@link ForkJoinPool#commonPool()}
     * <p>
     * Returns when all EventLoops are stopped, safe to pass nulls or collections containing nulls
     *
     * @param eventLoops A list of EventLoops or collections of event loops
     */
    public static void stopAll(Object... eventLoops) {
        List<Callable<Void>> eventLoopStoppers = new ArrayList<>();
        addAllEventLoopStoppers(Arrays.asList(eventLoops), eventLoopStoppers);
        for (Future<Void> voidFuture : ForkJoinPool.commonPool().invokeAll(eventLoopStoppers)) {
            try {
                voidFuture.get();
            } catch (ExecutionException e) {
                Jvm.error().on(EventLoops.class, "Error stopping event loop", e);
            } catch (InterruptedException e) {
                Jvm.warn().on(EventLoops.class, "Interrupted waiting for event loops to stop");
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void addAllEventLoopStoppers(Collection<?> collection, List<Callable<Void>> stoppers) {
        for (Object o : collection) {
            if (o == null) {
                continue;
            }
            if (o instanceof EventLoop) {
                stoppers.add(() -> {
                    ((EventLoop) o).stop();
                    return null;
                });
            } else if (o instanceof Collection) {
                addAllEventLoopStoppers((Collection<?>) o, stoppers);
            } else {
                Jvm.warn().on(EventLoops.class, "Unexpected object passed to EventLoops.stop(): " + o);
            }
        }
    }
}
