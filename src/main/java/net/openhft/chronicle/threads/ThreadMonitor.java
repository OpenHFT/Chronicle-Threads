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

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code ThreadMonitor} interface extends {@link EventHandler} to represent a monitoring handler within
 * an event loop. It is specifically designated with a {@link HandlerPriority} of {@code MONITOR}, allowing
 * it to oversee thread operations and manage the lifecycle of monitored threads.
 *
 * <p>Classes implementing this interface can be used to observe and log the behavior of threads, detect
 * delays or timing issues, and ensure that event loops are running as expected.</p>
 */
public interface ThreadMonitor extends EventHandler {

    /**
     * Specifies the priority of this handler as {@link HandlerPriority#MONITOR}.
     * This priority level is typically used for monitoring tasks within an event loop.
     *
     * @return the {@code MONITOR} priority level
     */
    @Override
    default @NotNull HandlerPriority priority() {
        return HandlerPriority.MONITOR;
    }
}
