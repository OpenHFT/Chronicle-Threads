/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Marker interface to show we support {@link #pause(long, TimeUnit)}
 */
public interface TimingPauser extends Pauser {

    /**
     * Pauses but keep tracks of accumulated pause time and throws if timeout exceeded
     *
     * @param timeout  timeout
     * @param timeUnit unit
     * @throws TimeoutException thrown if timeout passes
     */
    @Override
    void pause(long timeout, TimeUnit timeUnit) throws TimeoutException;
}
