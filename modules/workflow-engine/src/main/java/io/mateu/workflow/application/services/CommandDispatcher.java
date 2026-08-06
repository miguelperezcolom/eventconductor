package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.CommandPublisher;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place a targeted operator command (retry / restart / pause / resume a process by id) is
 * routed. Sharded ({@code workflow.sharding.enabled=true}) → the {@link CommandPublisher}, which sends
 * it to the shard that owns the process. Single cluster (default) → this shard's own {@code upstream},
 * exactly as before, where Kafka partitioning already delivers it to the owning pod.
 *
 * <p>So the control-plane entry points (the MCP tools, the UI actions) call {@code dispatch(command)}
 * and stay ignorant of sharding.
 */
@Component
public class CommandDispatcher {

    private final UpstreamEventPublisher upstreamEventPublisher;
    private final CommandPublisher commandPublisher;
    private final boolean sharding;

    public CommandDispatcher(UpstreamEventPublisher upstreamEventPublisher,
                             CommandPublisher commandPublisher,
                             @Value("${workflow.sharding.enabled:false}") boolean sharding) {
        this.upstreamEventPublisher = upstreamEventPublisher;
        this.commandPublisher = commandPublisher;
        this.sharding = sharding;
    }

    public void dispatch(DomainEvent command) {
        if (sharding) {
            commandPublisher.publish(command);
        } else {
            upstreamEventPublisher.publish(command);
        }
    }
}
