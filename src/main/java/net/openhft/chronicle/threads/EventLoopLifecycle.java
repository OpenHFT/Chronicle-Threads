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

/**
 * Defines the life-cycle stages of an event loop, including possible state transitions.
 *
 * <p>The transitions occur in the following order:
 * <pre>
 *      +-------------------------------------------------+
 *      |                                                 v
 * +---------+    +-----------+    +-----------+    +-----------+
 * |   NEW   |---&gt;|  STARTED  |---&gt;|  STOPPING |---&gt;|  STOPPED  |
 * +---------+    +-----------+    +-----------+    +-----------+
 * </pre>
 */
public enum EventLoopLifecycle {

    /**
     * The event loop has been created but has not yet started.
     */
    NEW(false),

    /**
     * The event loop has been started but is not yet stopping.
     */
    STARTED(false),

    /**
     * Indicates that stop has been called, but some event handlers are still processing.
     */
    STOPPING(true),

    /**
     * The event loop has fully stopped, with no handlers currently active.
     */
    STOPPED(true);

    private final boolean stopped;

    /**
     * Constructs an instance of {@link EventLoopLifecycle}.
     *
     * @param stopped {@code true} if the event loop is in a stopped or stopping state, {@code false} otherwise
     */
    EventLoopLifecycle(boolean stopped) {
        this.stopped = stopped;
    }

    /**
     * Checks if the event loop is either in the stopping or stopped state.
     *
     * @return {@code true} if the event loop is stopping or stopped, {@code false} otherwise
     */
    public boolean isStopped() {
        return stopped;
    }
}
