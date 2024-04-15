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
 * The life-cycle of an event loop
 * <p>
 * possible transitions include:
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
     * The event loop has been created but not yet started
     */
    NEW(false),

    /**
     * The event loop has been started but not yet stopped
     */
    STARTED(false),

    /**
     * Stop has been called, but some handlers are yet to complete
     */
    STOPPING(true),

    /**
     * The event loop has been stopped
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
