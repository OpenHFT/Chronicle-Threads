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
import net.openhft.chronicle.core.observable.Observable;
import net.openhft.chronicle.core.observable.StateReporter;
import net.openhft.chronicle.core.threads.EventLoop;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public final class EventLoops {
    static final boolean TRACE_EVENT_LOOPS = Jvm.getBoolean("trace.eventLoops");
    static final List<Reference<EventLoop>> EVENT_LOOPS = TRACE_EVENT_LOOPS
            ? Collections.synchronizedList(new ArrayList<>())
            : Collections.emptyList();

    // Suppresses default constructor, ensuring non-instantiability.
    private EventLoops() {
    }

    public static void addEventLoop(EventLoop eventLoop) {
        if (TRACE_EVENT_LOOPS) {
            EVENT_LOOPS.removeIf(r -> r.get() == null);
            EVENT_LOOPS.add(new WeakReference<>(eventLoop));
        }
    }

    public static void removeEventLoop(EventLoop eventLoop) {
        if (TRACE_EVENT_LOOPS) {
            EVENT_LOOPS.removeIf(r -> r.get() == null || r.get() == eventLoop);
        }
    }

    public static void dumpEventLoops(StateReporter stateReporter) {
        EVENT_LOOPS.removeIf(r -> r.get() == null);
        synchronized (EVENT_LOOPS) {
            final List<Observable> nonGCdObservableEventLoops = EVENT_LOOPS.stream()
                    .map(Reference::get)
                    .filter(Objects::nonNull)
                    .filter(el -> el instanceof Observable)
                    .map(el -> (Observable) el)
                    .collect(Collectors.toList());
            stateReporter.writeChildren("eventLoops", nonGCdObservableEventLoops);
        }
    }

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
