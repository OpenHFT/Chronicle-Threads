/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.HandlerPriority;

import java.util.function.Supplier;

class MediumEventLoopTest extends AbstractEventLoopTest {

    @Override
    protected Supplier<? extends MediumEventLoop> eventLoopSupplier() {
        return () -> new MediumEventLoop(null, "name", Pauser.balanced(), true, null);
    }

    @Override
    protected Supplier<? extends MediumEventLoop> concurrentLoopSupplier() {
        return () -> new MediumEventLoop(null, "test", Pauser.balanced(), false, "any");
    }

    @Override
    protected HandlerPriority firstPriority() {
        return HandlerPriority.MEDIUM;
    }

    @Override
    protected HandlerPriority secondPriority() {
        return HandlerPriority.HIGH;
    }
}
