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

package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.threads.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoopStateRendererTest extends ThreadsTestCommon {

    @Test
    void isNullSafe() {
        assertEquals("Foo event loop is null", EventLoopStateRenderer.INSTANCE.render("Foo", null));
    }

    @Test
    void testCanRenderMediumEventLoop() {
        try (final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "foobar", Pauser.sleepy(), true, "any")) {
            mediumEventLoop.start();
            while (!mediumEventLoop.isAlive()) {
                Jvm.pause(10);
            }
            final String dump = EventLoopStateRenderer.INSTANCE.render("Medium", mediumEventLoop);
            Jvm.startup().on(EventLoopStateRendererTest.class, dump);
            assertTrue(dump.contains("Medium event loop state"));
            assertTrue(dump.contains("Closed: false"));
            assertTrue(dump.contains("Closing: false"));
            assertTrue(dump.contains("Lifecycle: STARTED"));
            assertTrue(dump.contains("Thread state: "));
        }
    }

    @Test
    void testCanRenderStoppedMediumEventLoop() {
        try (final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "foobar", Pauser.sleepy(), true, "any")) {
            mediumEventLoop.start();
            while (!mediumEventLoop.isAlive()) {
                Jvm.pause(10);
            }
            mediumEventLoop.stop();
            while (!mediumEventLoop.isStopped()) {
                Jvm.pause(10);
            }
            final String dump = EventLoopStateRenderer.INSTANCE.render("Medium", mediumEventLoop);
            Jvm.startup().on(EventLoopStateRendererTest.class, dump);
            assertTrue(dump.contains("Medium event loop state"));
            assertTrue(dump.contains("Closed: false"));
            assertTrue(dump.contains("Closing: false"));
            assertTrue(dump.contains("Lifecycle: STOPPED"));
            assertTrue(dump.contains("Thread state: "));
        }
    }

    @Test
    void testCanRenderUnstartedMediumEventLoop() {
        try (final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "foobar", Pauser.sleepy(), true, "any")) {
            final String dump = EventLoopStateRenderer.INSTANCE.render("Medium", mediumEventLoop);
            Jvm.startup().on(EventLoopStateRendererTest.class, dump);
            assertTrue(dump.contains("Medium event loop state"));
            assertTrue(dump.contains("Closed: false"));
            assertTrue(dump.contains("Closing: false"));
            assertTrue(dump.contains("Lifecycle: NEW"));
        }
    }

    @Test
    void testCanRenderMonitorEventLoop() {
        try (final MonitorEventLoop monitorEventLoop = new MonitorEventLoop(null, Pauser.sleepy())) {
            monitorEventLoop.start();
            while (!monitorEventLoop.isAlive()) {
                Jvm.pause(10);
            }
            final String dump = EventLoopStateRenderer.INSTANCE.render("Monitor", monitorEventLoop);
            Jvm.startup().on(EventLoopStateRendererTest.class, dump);
            assertTrue(dump.contains("Monitor event loop state"));
            assertTrue(dump.contains("Closed: false"));
            assertTrue(dump.contains("Closing: false"));
            assertTrue(dump.contains("Lifecycle: STARTED"));
        }
    }

    @Test
    void testCanRenderEventGroup() {
        try (final EventLoop eventGroup = EventGroup.builder().build()) {
            eventGroup.start();
            while (!eventGroup.isAlive()) {
                Jvm.pause(10);
            }
            final String dump = EventLoopStateRenderer.INSTANCE.render("EG", eventGroup);
            Jvm.startup().on(EventLoopStateRendererTest.class, dump);
            assertTrue(dump.contains("EG event loop state"));
            assertTrue(dump.contains("Closed: false"));
            assertTrue(dump.contains("Closing: false"));
            assertTrue(dump.contains("Lifecycle: STARTED"));
        }
    }
}
