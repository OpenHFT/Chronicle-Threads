/*
 * Copyright 2016-2020 chronicle.software
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
package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;

/**
 * Configuration values for event loop behaviour.
 *
 * <p>The {@code ACCEPT_HANDLER_MOD_COUNT} system property specifies how often
 * new accept handlers are inserted to avoid starvation. A value of zero
 * disables this feature. If the property is absent the
 * {@link #DEFAULT_ACCEPT_HANDLER_MOD_COUNT default} is used. The
 * {@link #IS_ACCEPT_HANDLER_MOD_COUNT} flag reveals whether re-arming is
 * enabled.</p>
 */
public enum EventLoopUtil {
    ; // none

    /** Fallback when {@code eventloop.accept.mod} is not set. */
    private static final int DEFAULT_ACCEPT_HANDLER_MOD_COUNT = 128;

    /** Interval for re-adding accept handlers. */
    public static final int ACCEPT_HANDLER_MOD_COUNT =
            Jvm.getInteger("eventloop.accept.mod", DEFAULT_ACCEPT_HANDLER_MOD_COUNT);

    /** True when accept handler re-arming is active. */
    public static final boolean IS_ACCEPT_HANDLER_MOD_COUNT = ACCEPT_HANDLER_MOD_COUNT > 0;
}
