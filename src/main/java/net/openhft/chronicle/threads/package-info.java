/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
/**
 * Event loop implementations and utilities for running deterministic
 * single-threaded event handlers.  {@link net.openhft.chronicle.core.threads.EventLoop EventLoop}
 * implementations are aggregated by {@link net.openhft.chronicle.threads.EventGroup EventGroup}.
 * Pauser strategies ({@link net.openhft.chronicle.threads.Pauser Pauser}) control the
 * trade off between latency and CPU use when no work is available.
 * <p>
 * Typical usage involves building an {@code EventGroup} via
 * {@link net.openhft.chronicle.threads.EventGroupBuilder}, installing handlers then calling
 * {@code start()}.  Handlers are executed on the same thread, avoiding locks in hot paths.
 * <p>
 * Behaviour such as loop monitoring or thread counts can be configured via system properties
 * (see {@code systemProperties.adoc}).
 */
package net.openhft.chronicle.threads;
