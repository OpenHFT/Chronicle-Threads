package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.threads.HandlerPriority;

import java.util.EnumSet;
import java.util.Set;

import static net.openhft.chronicle.threads.EventGroup.REPLICATION_EVENT_PAUSE_TIME;

public class EventGroupBuilder {

    private boolean daemon = true;
    private Pauser pauser;
    private Pauser replicationPauser;
    private String binding = "none";
    private String replicationBinding = "none";
    private String name = "";
    private int concurrentThreadsNum = EventGroup.CONC_THREADS;
    private String concurrentBinding = "none";
    private Pauser concurrentPauser;
    private Set<HandlerPriority> priorities = EnumSet.allOf(HandlerPriority.class);
    private String defaultBinding = "none";

    public static EventGroupBuilder builder() {
        return new EventGroupBuilder();
    }

    private EventGroupBuilder() {
    }

    public EventGroup build() {
        return new EventGroup(daemon,
                pauserOrDefault(),
                replicationPauser,
                defaultBinding(binding),
                defaultBinding(replicationBinding),
                name,
                concurrentThreadsNum,
                defaultBinding(concurrentBinding),
                concurrentPauserOrDefault(),
                priorities);
    }

    private Pauser pauserOrDefault() {
        return pauser != null ? pauser : Pauser.balanced();
    }

    private Pauser concurrentPauserOrDefault() {
        return concurrentPauser != null ? concurrentPauser : Pauser.balancedUpToMillis(REPLICATION_EVENT_PAUSE_TIME);
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

    public EventGroupBuilder withName(String name) {
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

    public EventGroupBuilder withConcurrentPauser(Pauser concurrentPauser) {
        this.concurrentPauser = concurrentPauser;
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
