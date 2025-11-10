//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//

/*
 * Copyright 2016-2025 chronicle.software
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
 * The life-cycle of an event loop. The state moves from {@link #NEW} to
 * {@link #STARTED} when {@code start()} is invoked. A request to {@code stop()}
 * moves the loop to {@link #STOPPING} and once all handlers have completed it
 * becomes {@link #STOPPED}.
 * <p>
 * Possible transitions include:
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
     * The event loop has been created but not yet started. Only
     * {@code start()} or {@code stop()} are meaningful in this state.
     */
    NEW(false),

    /**
     * The event loop is running. Calling {@code stop()} moves it to
     * {@link #STOPPING}.
     */
    STARTED(false),

    /**
     * {@code stop()} has been called and handlers are finishing. Further calls
     * to {@code stop()} wait for completion.
     */
    STOPPING(true),

    /**
     * The event loop has been stopped and cannot be restarted.
     */
    STOPPED(true);

    private final boolean stopped;

    EventLoopLifecycle(boolean stopped) {
        this.stopped = stopped;
    }

    public boolean isStopped() {
        return stopped;
    }
}
