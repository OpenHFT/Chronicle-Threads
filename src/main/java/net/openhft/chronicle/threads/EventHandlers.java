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

/**
 * A singleton implementation of {@link EventHandler} for a no-operation (NOOP) event handler.
 * This handler can be used as a placeholder where an {@link EventHandler} is required but no action is needed.
 */
enum EventHandlers implements EventHandler {

    /**
     * A no-operation handler that performs no actions.
     * Useful as a default or placeholder event handler.
     */
    NOOP {
        /**
         * Defines the action to be taken by this handler, which is none.
         *
         * @return {@code false} indicating that no action was performed.
         */
        @Override
        public boolean action() {
            return false;
        }
    }
}
