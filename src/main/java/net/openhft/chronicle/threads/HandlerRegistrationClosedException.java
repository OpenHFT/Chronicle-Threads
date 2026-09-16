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
public class HandlerRegistrationClosedException extends ClosedIllegalStateException {
    private static final long serialVersionUID = 0L;

    public HandlerRegistrationClosedException(String message) {
        super(message);
    }

    HandlerRegistrationClosedException(ClosedIllegalStateException cause) {
        super(cause.getMessage(), cause);
    }
}
