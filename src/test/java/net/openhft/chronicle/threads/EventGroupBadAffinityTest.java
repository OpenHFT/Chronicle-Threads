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

import net.openhft.chronicle.core.threads.EventLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class EventGroupBadAffinityTest extends ThreadsTestCommon {

    @Timeout(5_000)
    @Test
    public void testInvalidAffinity() {
        expectException("Cannot parse 'xxx'");
        ignoreException("Timed out waiting for start!");
        try (final EventLoop eventGroup = EventGroup.builder().withBinding("xxx").build()) {
            assertThrows(IllegalStateException.class, eventGroup::start);
        }
    }
}
