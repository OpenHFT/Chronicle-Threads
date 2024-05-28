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

public enum EventLoopUtil {
    ; // none
    private static final int DEFAULT_ACCEPT_HANDLER_MOD_COUNT = 128;
    public static final int ACCEPT_HANDLER_MOD_COUNT = Jvm.getInteger("eventloop.accept.mod", DEFAULT_ACCEPT_HANDLER_MOD_COUNT);
    public static final boolean IS_ACCEPT_HANDLER_MOD_COUNT = ACCEPT_HANDLER_MOD_COUNT > 0;
}
