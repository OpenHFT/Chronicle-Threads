/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.HandlerPriority;

import java.util.function.Supplier;

class VanillaEventLoopTest extends AbstractEventLoopTest {

    @Override
    protected Supplier<? extends MediumEventLoop> eventLoopSupplier() {
        return () -> new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null, VanillaEventLoop.ALLOWED_PRIORITIES);
    }

    @Override
    protected Supplier<? extends MediumEventLoop> concurrentLoopSupplier() {
        return () -> new VanillaEventLoop(null, "name", Pauser.balanced(), 1000L, true, null, VanillaEventLoop.ALLOWED_PRIORITIES);
    }

    @Override
    protected HandlerPriority firstPriority() {
        return HandlerPriority.TIMER;
    }

    @Override
    protected HandlerPriority secondPriority() {
        return HandlerPriority.DAEMON;
    }
}
