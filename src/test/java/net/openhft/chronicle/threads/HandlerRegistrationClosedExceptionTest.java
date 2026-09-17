/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static net.openhft.chronicle.threads.TestEventHandlers.CountingHandler;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HandlerRegistrationClosedExceptionTest extends ThreadsTestCommon {
    enum LoopType {
        MEDIUM, VANILLA, GROUP, BLOCKING;

        AbstractLifecycleEventLoop create() {
            switch (this) {
                case MEDIUM:
                    return new MediumEventLoop(null, "admission", Pauser.balanced(), true, null);
                case VANILLA:
                    return new VanillaEventLoop(null, "admission", Pauser.balanced(), 10, true, null,
                            VanillaEventLoop.ALLOWED_PRIORITIES);
                case GROUP:
                    return EventGroup.builder().build();
                default:
                    return new BlockingEventLoop("admission");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(LoopType.class)
    @SuppressWarnings("try")
    void closedLoopRejectsWithoutTakingOwnership(LoopType type) throws Exception {
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
        try (EventLoop loop = type.create()) {
            loop.close();
            HandlerRegistrationClosedException failure = assertThrows(HandlerRegistrationClosedException.class,
                    () -> loop.addHandler(handler));
            assertInstanceOf(ClosedIllegalStateException.class, failure);
            assertEquals(0, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
        } finally {
            handler.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = LoopType.class, names = {"MEDIUM", "VANILLA", "GROUP"})
    void checkedStoppedLoopHasDistinguishableRejection(LoopType type) throws Exception {
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
        try (AbstractLifecycleEventLoop loop = type.create()) {
            loop.stop();
            assertThrows(HandlerRegistrationRejectedException.class, () -> loop.addHandlerOrThrow(handler));
            assertEquals(0, handler.loopFinishedCalled());
            assertEquals(0, handler.closeCalled());
        } finally {
            handler.close();
        }
    }

    @Test
    void missingPriorityIsNotAClosedRegistrationEvenWhenGroupIsStopped() throws Exception {
        CountingHandler handler = new CountingHandler(HandlerPriority.MEDIUM);
        try (EventLoop loop = EventGroup.builder().withPriorities(HandlerPriority.BLOCKING).build()) {
            loop.stop();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> loop.addHandler(handler));
            assertFalse(failure instanceof HandlerRegistrationClosedException);
            assertTrue(failure.getMessage().startsWith("Cannot add MEDIUM"));
        } finally {
            handler.close();
        }
    }

    @Test
    void callbackFailureIsNotReclassifiedAsRejection() {
        ClosedIllegalStateException failure = new ClosedIllegalStateException("application callback closed");
        EventHandler handler = new EventHandler() {
            @Override
            public boolean action() {
                return false;
            }

            @Override
            public HandlerPriority priority() {
                throw failure;
            }
        };
        try (EventLoop loop = EventGroup.builder().build()) {
            assertSame(failure, assertThrows(ClosedIllegalStateException.class, () -> loop.addHandler(handler)));
        }
    }
}
