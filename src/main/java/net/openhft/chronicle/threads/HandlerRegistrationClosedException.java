/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.io.ClosedIllegalStateException;

/**
 * Registration was rejected because the receiving loop's admission has closed.
 * The caller retains ownership of the handler, including its finish and close callbacks.
 * Configuration errors and exceptions from handler callbacks do not imply this outcome.
 *
 * <p>The closed resource is the registration channel: the loop may still be stopping.
 * Extending {@link ClosedIllegalStateException} preserves existing closed-loop catch clauses.</p>
 */
//! A specific reason avoids inferring shutdown from mutable loop state or exception-message text.
//! Keep ClosedIllegalStateException as the superclass so existing closed-loop catch clauses still work.
//! Regression: HandlerRegistrationClosedExceptionTest.closedLoopRejectsWithoutTakingOwnership checks both types.
public class HandlerRegistrationClosedException extends ClosedIllegalStateException {
    private static final long serialVersionUID = 0L;

    public HandlerRegistrationClosedException(String message) {
        super(message);
    }

    HandlerRegistrationClosedException(ClosedIllegalStateException cause) {
        super(cause.getMessage(), cause);
    }
}
