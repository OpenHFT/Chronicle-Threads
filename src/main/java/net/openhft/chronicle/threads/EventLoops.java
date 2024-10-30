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
import net.openhft.chronicle.core.threads.EventLoop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

/**
 * Utility class for managing the stopping of multiple {@link EventLoop} instances concurrently.
 * Provides methods to stop individual or grouped {@link EventLoop} instances, leveraging parallel
 * processing with {@link ForkJoinPool#commonPool()}.
 * <p>
 * This class is non-instantiable, as its purpose is to provide static utility methods.
 */
public final class EventLoops {

    /**
     * Private constructor to prevent instantiation.
     */
    private EventLoops() {
    }

    /**
     * Stops multiple {@link EventLoop} instances concurrently using the {@link ForkJoinPool#commonPool()}.
     * This method is designed to handle nested collections and safely ignores null elements.
     *
     * <p>This method returns only once all provided {@link EventLoop} instances have stopped. Any errors
     * or interruptions encountered during the stopping process are logged.
     *
     * @param eventLoops an array of {@link EventLoop} instances or collections containing {@link EventLoop} instances
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

    /**
     * Recursively adds stop tasks for each {@link EventLoop} instance within the specified collection.
     * If the collection contains nested collections, this method will add tasks for each nested {@link EventLoop}.
     *
     * @param collection the collection of objects to be processed
     * @param stoppers   the list to which stop tasks are added
     */
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
