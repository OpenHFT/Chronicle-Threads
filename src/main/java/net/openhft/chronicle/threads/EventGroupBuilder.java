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

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.util.Builder;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

import static net.openhft.chronicle.threads.EventGroup.REPLICATION_EVENT_PAUSE_TIME;

/**
 * Builder for creating instances of {@link EventGroup}, allowing for flexible configuration
 * of various parameters such as thread type, bindings, pausers, and handler priorities.
 * Implements {@link Supplier} to integrate with configurations expecting a {@code Supplier<EventLoop>}.
 */
public class EventGroupBuilder implements Builder<EventLoop> {

    private boolean daemon = true;  // Specifies if the EventGroup should run as a daemon
    private Pauser pauser;  // Main pauser for the EventGroup
    private Pauser replicationPauser;  // Pauser for replication-related event loops
    private String binding = "none";  // Binding configuration for the main event loop
    private String replicationBinding = "none";  // Binding configuration for replication event loops
    @NotNull
    private String name = "";  // Name of the EventGroup instance
    private int concurrentThreadsNum = EventGroup.CONC_THREADS;  // Number of concurrent threads
    private String concurrentBinding = "none";  // Binding configuration for concurrent event loops
    @NotNull
    private Supplier<Pauser> concurrentPauserSupplier = () -> Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);  // Supplier for concurrent pauser
    private Set<HandlerPriority> priorities = EnumSet.allOf(HandlerPriority.class);  // Set of handler priorities
    private String defaultBinding = "none";  // Default binding configuration
    @NotNull
    private Supplier<Pauser> blockingPauserSupplier = PauserMode.balanced;

    /**
     * Creates a new builder instance for {@link EventGroup}.
     *
     * @return a new {@link EventGroupBuilder} instance
     */
    public static EventGroupBuilder builder() {
        return new EventGroupBuilder();
    }

    private EventGroupBuilder() {
    }

    /**
     * Builds and returns an {@link EventGroup} instance based on the builder's current configuration.
     *
     * @return a configured {@link EventGroup} instance
     */
    @SuppressWarnings("deprecation")
    @Override
    public EventGroup build() {
        return new EventGroup(daemon,
                pauserOrDefault(),
                replicationPauser,
                defaultBinding(binding),
                defaultBinding(replicationBinding),
                name,
                concurrentThreadsNum,
                defaultBinding(concurrentBinding),
                concurrentPauserSupplier,
                priorities,
                blockingPauserSupplier);
    }

    /**
     * Provides the configured pauser or a default balanced pauser if not set.
     *
     * @return the configured or default {@link Pauser} instance
     */
    @NotNull
    private Pauser pauserOrDefault() {
        return pauser != null ? pauser : Pauser.balanced();
    }

    /**
     * Resolves the specified binding or falls back to the default binding if null.
     *
     * @param specifiedBinding the specified binding
     * @return the resolved binding
     */
    private String defaultBinding(String specifiedBinding) {
        return specifiedBinding != null ? specifiedBinding : defaultBinding;
    }

    /**
     * Sets the default binding configuration to "any".
     *
     * @return this builder instance for chaining
     */
    public EventGroupBuilder bindingAnyByDefault() {
        this.defaultBinding = "any";
        return this;
    }

    /**
     * Sets the default binding configuration to "none".
     *
     * @return this builder instance for chaining
     */
    public EventGroupBuilder bindingNoneByDefault() {
        this.defaultBinding = "none";
        return this;
    }

    /**
     * Sets whether the EventGroup should run as a daemon.
     *
     * @param daemon whether the event loop should be a daemon
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withDaemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    /**
     * Sets the binding configuration for the main event loop.
     *
     * @param binding the binding configuration
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withBinding(String binding) {
        this.binding = binding;
        return this;
    }

    /**
     * Sets the primary pauser for the EventGroup.
     *
     * @param pauser the {@link Pauser} instance
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withPauser(Pauser pauser) {
        this.pauser = pauser;
        return this;
    }

    /**
     * Sets the binding configuration for replication event loops.
     *
     * @param replicationBinding the replication binding configuration
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withReplicationBinding(String replicationBinding) {
        this.replicationBinding = replicationBinding;
        return this;
    }

    /**
     * Sets the pauser for replication event loops.
     *
     * @param replicationPauser the {@link Pauser} instance for replication
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withReplicationPauser(Pauser replicationPauser) {
        this.replicationPauser = replicationPauser;
        return this;
    }

    /**
     * Sets the supplier for a blocking pauser.
     *
     * @param blockingPauserSupplier the supplier of the blocking {@link Pauser}
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withBlockingPauserSupplier(@NotNull Supplier<Pauser> blockingPauserSupplier) {
        this.blockingPauserSupplier = blockingPauserSupplier;
        return this;
    }

    /**
     * Sets the name of the EventGroup.
     *
     * @param name the name to assign
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withName(@NotNull String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the number of concurrent threads for the EventGroup.
     *
     * @param concurrentThreadsNum the number of concurrent threads
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withConcurrentThreadsNum(int concurrentThreadsNum) {
        this.concurrentThreadsNum = concurrentThreadsNum;
        return this;
    }

    /**
     * Sets the binding configuration for concurrent event loops.
     *
     * @param concurrentBinding the binding for concurrent event loops
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withConcurrentBinding(String concurrentBinding) {
        this.concurrentBinding = concurrentBinding;
        return this;
    }

    /**
     * Sets the supplier for the concurrent pauser.
     *
     * @param concurrentPauserSupplier the supplier of the concurrent {@link Pauser}
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withConcurrentPauserSupplier(@NotNull Supplier<Pauser> concurrentPauserSupplier) {
        this.concurrentPauserSupplier = concurrentPauserSupplier;
        return this;
    }

    /**
     * Sets the priorities for handler processing in the EventGroup.
     *
     * @param priorities the set of {@link HandlerPriority} values
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withPriorities(Set<HandlerPriority> priorities) {
        this.priorities = priorities;
        return this;
    }

    /**
     * Sets the priorities for handler processing using varargs input.
     *
     * @param firstPriority the first priority
     * @param priorities    additional priorities
     * @return this builder instance for chaining
     */
    public EventGroupBuilder withPriorities(HandlerPriority firstPriority, HandlerPriority... priorities) {
        return withPriorities(EnumSet.of(firstPriority, priorities));
    }
}
