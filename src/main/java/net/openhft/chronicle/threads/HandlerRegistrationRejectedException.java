/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.chronicle.threads;

/**
 * A checked registration attempt was rejected before the loop took handler ownership.
 * The caller must finish and close the unused handler, or retain it for another loop.
 * This exception is reserved for lifecycle rejection, not configuration or callback errors.
 */
//! An explicit checked API obliges callers to handle or declare lifecycle rejection.
//! Regression: HandlerAdmissionTest.checkedExceptionMustBeCaughtOrDeclared.
public final class HandlerRegistrationRejectedException extends Exception {
    private static final long serialVersionUID = 0L;

    public HandlerRegistrationRejectedException(String message) {
        super(message);
    }
}
