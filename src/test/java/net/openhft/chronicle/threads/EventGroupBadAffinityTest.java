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

import net.openhft.chronicle.core.threads.EventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EventGroupBadAffinityTest extends ThreadsTestCommon {

    /**
     * Ensures that an invalid CPU affinity string fails fast so that
     * misconfigured deployments do not run with unexpected processor binding.
     */
    @Timeout(5_000)
    @Test
    void testInvalidAffinity() {
        expectException("Cannot parse 'xxx'");
        ignoreException("Timed out waiting for start!");
        try (final EventLoop eventGroup = EventGroup.builder().withBinding("xxx").build()) {
            assertThrows(TimeoutException.class, eventGroup::start);
        }
    }
}
