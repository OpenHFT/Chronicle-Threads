/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
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
 * Implements the builder pattern for {@link EventGroup}. All options are
 * optional so a builder can be created and only a few settings changed before
 * calling {@link #build()}.
 *
 * <p>The default builder creates daemon threads with balanced pausers, no CPU
 * binding and support for every {@link HandlerPriority}. The call to
 * {@link #build()} returns a new group</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * EventGroup eg = EventGroupBuilder.builder()
 *         .withName("trade")
 *         .withDaemon(false)
 *         .withBinding("0-3")
 *         .withPriorities(EnumSet.of(
 *                 HandlerPriority.BLOCKING,
 *                 HandlerPriority.CONCURRENT))
 *         .build();
 * </pre>
 */
public class EventGroupBuilder implements Builder<EventLoop> {

    private boolean daemon = true;
    private Pauser pauser;
    private Pauser replicationPauser;
    private String binding = "none";
    private String replicationBinding = "none";
    @NotNull
    private String name = "";
    private int concurrentThreadsNum = EventGroup.CONC_THREADS;
    private String concurrentBinding = "none";
    @NotNull
    private Supplier<Pauser> concurrentPauserSupplier = () -> Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);
    private Set<HandlerPriority> priorities = EnumSet.allOf(HandlerPriority.class);
    private String defaultBinding = "none";
    @NotNull
    private Supplier<Pauser> blockingPauserSupplier = PauserMode.balanced;
    private boolean privateGroup;

    /**
     * Creates a builder with all default settings.
     *
     * @return a new {@code EventGroupBuilder}
     */
    public static EventGroupBuilder builder() {
        return new EventGroupBuilder();
    }

    private EventGroupBuilder() {
    }

    /**
     * Constructs the group using the current settings. The returned instance is
     * created but not started and is therefore in the
     * {@link net.openhft.chronicle.threads.EventLoopLifecycle#NEW} state.
     */
    @SuppressWarnings("deprecation")
    @Override
    public EventGroup build() {
        EventGroup eventGroup = new EventGroup(daemon,
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
        eventGroup.privateGroup(privateGroup);
        return eventGroup;
    }

    @NotNull
    private Pauser pauserOrDefault() {
        return pauser != null ? pauser : Pauser.balanced();
    }

    private String defaultBinding(String specifiedBinding) {
        return specifiedBinding != null ? specifiedBinding : defaultBinding;
    }

    /**
     * Uses {@code "any"} as the default binding for loops where no explicit
     * binding has been supplied.
     *
     * @return this builder
     */
    public EventGroupBuilder bindingAnyByDefault() {
        this.defaultBinding = "any";
        return this;
    }

    /**
     * Uses {@code "none"} as the default binding when a specific binding is not
     * provided.
     *
     * @return this builder
     */
    public EventGroupBuilder bindingNoneByDefault() {
        this.defaultBinding = "none";
        return this;
    }

    /**
     * Determines whether created threads are daemon threads. Default is
     * {@code true}.
     *
     * @param daemon set {@code false} for user threads
     * @return this builder
     */
    public EventGroupBuilder withDaemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    /**
     * Sets the CPU affinity for the core loop. If not set the default binding
     * ({@code "none"} or {@code "any"}) is used. This affects other loops that
     * rely on the default binding unless overridden.
     *
     * @param binding affinity specification understood by Chronicle Affinity
     * @return this builder
     */
    public EventGroupBuilder withBinding(String binding) {
        this.binding = binding;
        return this;
    }

    /**
     * Supplies the pauser for the core loop. If omitted a balanced pauser is
     * used. The replication loop also uses this pauser when no explicit
     * replication pauser is provided.
     *
     * @param pauser pauser to apply to the core loop
     * @return this builder
     */
    public EventGroupBuilder withPauser(Pauser pauser) {
        this.pauser = pauser;
        return this;
    }

    /**
     * Sets the binding for replication loops. Defaults to the same value as
     * {@link #withBinding(String)}.
     *
     * @param replicationBinding affinity for the replication loop
     * @return this builder
     */
    public EventGroupBuilder withReplicationBinding(String replicationBinding) {
        this.replicationBinding = replicationBinding;
        return this;
    }

    /**
     * Supplies the pauser for replication loops. When not supplied a balanced
     * pauser up to {@code REPLICATION_EVENT_PAUSE_TIME} is used.
     *
     * @param replicationPauser pauser for replication loops
     * @return this builder
     */
    public EventGroupBuilder withReplicationPauser(Pauser replicationPauser) {
        this.replicationPauser = replicationPauser;
        return this;
    }

    /**
     * Sets the pauser supplier used by the blocking loop. The default is
     * {@link PauserMode#balanced}.
     *
     * @param blockingPauserSupplier supplier of a pauser for blocking threads
     * @return this builder
     */
    public EventGroupBuilder withBlockingPauserSupplier(@NotNull Supplier<Pauser> blockingPauserSupplier) {
        this.blockingPauserSupplier = blockingPauserSupplier;
        return this;
    }

    /**
     * Sets the base name for the group and its threads. The default is an empty
     * string.
     *
     * @param name base name for created threads
     * @return this builder
     */
    public EventGroupBuilder withName(@NotNull String name) {
        this.name = name;
        return this;
    }

    /**
     * Specifies the number of concurrent event loops. Default is
     * {@link EventGroup#CONC_THREADS}.
     *
     * @param concurrentThreadsNum number of concurrent loops
     * @return this builder
     */
    public EventGroupBuilder withConcurrentThreadsNum(int concurrentThreadsNum) {
        this.concurrentThreadsNum = concurrentThreadsNum;
        return this;
    }

    /**
     * Sets the binding for concurrent loops. If not set the default binding is
     * used.
     *
     * @param concurrentBinding affinity for concurrent loops
     * @return this builder
     */
    public EventGroupBuilder withConcurrentBinding(String concurrentBinding) {
        this.concurrentBinding = concurrentBinding;
        return this;
    }

    /**
     * Supplies the pauser for concurrent loops. Default is a balanced pauser up
     * to {@code REPLICATION_EVENT_PAUSE_TIME}.
     *
     * @param concurrentPauserSupplier supplier for concurrent pausers
     * @return this builder
     */
    public EventGroupBuilder withConcurrentPauserSupplier(@NotNull Supplier<Pauser> concurrentPauserSupplier) {
        this.concurrentPauserSupplier = concurrentPauserSupplier;
        return this;
    }

    /**
     * Chooses which handler priorities the group will support. Loops for
     * priorities not included are not created. The default is all priorities.
     *
     * @param priorities set of priorities to enable
     * @return this builder
     */
    public EventGroupBuilder withPriorities(Set<HandlerPriority> priorities) {
        this.priorities = priorities;
        return this;
    }

    /**
     * Convenience overload to build a priority set from the given arguments.
     *
     * @param firstPriority first priority in the set
     * @param priorities remaining priorities
     * @return this builder
     */
    public EventGroupBuilder withPriorities(HandlerPriority firstPriority, HandlerPriority... priorities) {
        return withPriorities(EnumSet.of(firstPriority, priorities));
    }

    /**
     * Signifies this a private EventGroup, which will not be shared and can be shutdown independently.
     */
    public EventGroupBuilder withPrivateGroup(boolean privateGroup) {
        this.privateGroup = privateGroup;
        return this;
    }
}
