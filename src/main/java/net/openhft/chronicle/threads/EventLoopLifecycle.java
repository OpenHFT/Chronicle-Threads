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
 * |   NEW   |--->|  STARTED  |--->|  STOPPING |--->|  STOPPED  |
 * +---------+    +-----------+    +-----------+    +-----------+
 * </pre>
 */
public enum EventLoopLifecycle {
    /**
     * The event loop has been created but not yet started
     */
    NEW,

    /**
     * The event loop has been started but not yet stopped
     */
    STARTED,

    /**
     * Stop has been called, but some handlers are yet to complete
     */
    STOPPING,

    /**
     * The event loop has been stopped
     */
    STOPPED
}
