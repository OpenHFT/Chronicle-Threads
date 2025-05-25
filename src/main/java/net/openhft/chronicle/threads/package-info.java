/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
