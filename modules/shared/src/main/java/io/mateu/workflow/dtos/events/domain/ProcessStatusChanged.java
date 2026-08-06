package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;

import java.time.LocalDateTime;

/**
 * A process's status (and read-model summary) changed. This is the source of truth for the CQRS
 * process-index read model: it carries the full projected shape of the process, so a projector can
 * maintain the index from the event alone — in-process on a single database, or out-of-process
 * across sharded databases where it cannot read the write shard. Emitted only when
 * {@code workflow.projection.enabled} is on, so a deployment that does not run the read model pays
 * nothing for events nobody consumes.
 *
 * @param occurredAt when the transition was emitted (not when the projector consumes it). This is the
 *                   read model's ordering key: it is stamped in causal order as the write side runs,
 *                   so it stays correct even when a single node dispatches events out of that order
 *                   (an in-process cascade can run a just-created process to completion before the
 *                   creation's own seed event is dispatched), and it advances monotonically across
 *                   restarts as wall-clock time does — where a reset in-memory counter would not.
 * @param shardId    the shard that owns this process ({@code null} when not sharded). Stamped here, on
 *                   the owning shard as the event is emitted, so the read model records where the
 *                   process lives regardless of where the projector runs — in-process on each shard, or
 *                   a single fanned-out projector consuming every shard's events, which could not
 *                   otherwise tell them apart. It is what lets a targeted command (retry / cancel /
 *                   pause a process by id) be routed back to the shard that owns it.
 */
public record ProcessStatusChanged(
        String processId,
        String businessKey,
        String workflowDefinitionId,
        int workflowDefinitionVersion,
        String status,
        int completionPercentage,
        LocalDateTime created,
        LocalDateTime started,
        LocalDateTime finished,
        LocalDateTime occurredAt,
        String shardId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return processId;
    }
}
