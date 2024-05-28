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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.util.Builder;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

import static net.openhft.chronicle.threads.EventGroup.REPLICATION_EVENT_PAUSE_TIME;

/**
 * Builder for {@link EventGroup}. Implements {@link Supplier} so we can provide this to configuration
 * that expects a Supplier&lt;EventLoop&gt; (e.g. QE, FIX)
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

    public static EventGroupBuilder builder() {
        return new EventGroupBuilder();
    }

    private EventGroupBuilder() {
    }

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

    @NotNull
    private Pauser pauserOrDefault() {
        return pauser != null ? pauser : Pauser.balanced();
    }

    private String defaultBinding(String specifiedBinding) {
        return specifiedBinding != null ? specifiedBinding : defaultBinding;
    }

    public EventGroupBuilder bindingAnyByDefault() {
        this.defaultBinding = "any";
        return this;
    }

    public EventGroupBuilder bindingNoneByDefault() {
        this.defaultBinding = "none";
        return this;
    }

    public EventGroupBuilder withDaemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    public EventGroupBuilder withBinding(String binding) {
        this.binding = binding;
        return this;
    }

    public EventGroupBuilder withPauser(Pauser pauser) {
        this.pauser = pauser;
        return this;
    }

    public EventGroupBuilder withReplicationBinding(String replicationBinding) {
        this.replicationBinding = replicationBinding;
        return this;
    }

    public EventGroupBuilder withReplicationPauser(Pauser replicationPauser) {
        this.replicationPauser = replicationPauser;
        return this;
    }

    public EventGroupBuilder withBlockingPauserSupplier(@NotNull Supplier<Pauser> blockingPauserSupplier) {
        this.blockingPauserSupplier = blockingPauserSupplier;
        return this;
    }

    public EventGroupBuilder withName(@NotNull String name) {
        this.name = name;
        return this;
    }

    public EventGroupBuilder withConcurrentThreadsNum(int concurrentThreadsNum) {
        this.concurrentThreadsNum = concurrentThreadsNum;
        return this;
    }

    public EventGroupBuilder withConcurrentBinding(String concurrentBinding) {
        this.concurrentBinding = concurrentBinding;
        return this;
    }

    public EventGroupBuilder withConcurrentPauserSupplier(@NotNull Supplier<Pauser> concurrentPauserSupplier) {
        this.concurrentPauserSupplier = concurrentPauserSupplier;
        return this;
    }

    public EventGroupBuilder withPriorities(Set<HandlerPriority> priorities) {
        this.priorities = priorities;
        return this;
    }

    public EventGroupBuilder withPriorities(HandlerPriority firstPriority, HandlerPriority... priorities) {
        return withPriorities(EnumSet.of(firstPriority, priorities));
    }
}
