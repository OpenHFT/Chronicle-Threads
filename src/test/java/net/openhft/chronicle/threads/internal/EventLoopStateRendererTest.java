package net.openhft.chronicle.threads.internal;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.threads.EventGroup;
import net.openhft.chronicle.threads.MediumEventLoop;
import net.openhft.chronicle.threads.MonitorEventLoop;
import net.openhft.chronicle.threads.Pauser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoopStateRendererTest {

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