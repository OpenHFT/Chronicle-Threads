/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
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
        assertEquals("Foo event loop is null", EventLoopStateRenderer.INSTANCE.render("Foo", null), "null-safe render");
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
            assertTrue(dump.contains("Medium event loop state"), "started MediumEventLoop dump should contain 'Medium event loop state' header");
            assertTrue(dump.contains("Closed: false"), "started MediumEventLoop should show Closed: false");
            assertTrue(dump.contains("Closing: false"), "started MediumEventLoop should show Closing: false");
            assertTrue(dump.contains("Lifecycle: STARTED"), "started MediumEventLoop should show Lifecycle: STARTED");
            assertThreadDetailsPresent(dump);
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
            assertTrue(dump.contains("Medium event loop state"), "stopped MediumEventLoop dump should contain 'Medium event loop state' header");
            assertTrue(dump.contains("Closed: false"), "stopped MediumEventLoop should show Closed: false");
            assertTrue(dump.contains("Closing: false"), "stopped MediumEventLoop should show Closing: false");
            assertTrue(dump.contains("Lifecycle: STOPPED"), "stopped MediumEventLoop should show Lifecycle: STOPPED");
            assertThreadDetailsPresent(dump);
        }
    }

    @Test
    void testCanRenderUnstartedMediumEventLoop() {
        try (final MediumEventLoop mediumEventLoop = new MediumEventLoop(null, "foobar", Pauser.sleepy(), true, "any")) {
            final String dump = EventLoopStateRenderer.INSTANCE.render("Medium", mediumEventLoop);
            Jvm.startup().on(EventLoopStateRendererTest.class, dump);
            assertTrue(dump.contains("Medium event loop state"), "unstarted MediumEventLoop dump should contain 'Medium event loop state' header");
            assertTrue(dump.contains("Closed: false"), "unstarted MediumEventLoop should show Closed: false");
            assertTrue(dump.contains("Closing: false"), "unstarted MediumEventLoop should show Closing: false");
            assertTrue(dump.contains("Lifecycle: NEW"), "unstarted MediumEventLoop should show Lifecycle: NEW");
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
            assertTrue(dump.contains("Monitor event loop state"), "MonitorEventLoop dump should contain 'Monitor event loop state' header");
            assertTrue(dump.contains("Closed: false"), "MonitorEventLoop should show Closed: false");
            assertTrue(dump.contains("Closing: false"), "MonitorEventLoop should show Closing: false");
            assertTrue(dump.contains("Lifecycle: STARTED"), "MonitorEventLoop should show Lifecycle: STARTED");
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
            assertTrue(dump.contains("EG event loop state"), "EventGroup dump should contain 'EG event loop state' header");
            assertTrue(dump.contains("Closed: false"), "EventGroup should show Closed: false");
            assertTrue(dump.contains("Closing: false"), "EventGroup should show Closing: false");
            assertTrue(dump.contains("Lifecycle: STARTED"), "EventGroup should show Lifecycle: STARTED");
        }
    }

    private static void assertThreadDetailsPresent(String dump) {
        assertTrue(dump.contains("Thread state: ") || dump.contains("Thread is null"), "dump contains thread details");
    }
}
