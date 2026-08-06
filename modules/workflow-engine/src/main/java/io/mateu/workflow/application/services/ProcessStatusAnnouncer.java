package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Emits {@link ProcessStatusChanged} onto a process's outbox so the CQRS process-index projector can
 * maintain its read model — but only when {@code workflow.projection.enabled} is on.
 *
 * <p><b>One chokepoint, not many.</b> A process's status is set in a dozen scattered places (create,
 * recompute, cancel, the END-transition completion in the orchestration service, pause/resume,
 * retry/restart, the two saga terminals). Emitting from each is a standing invitation to miss one —
 * and a missed transition is a read model that silently lies. So instead every one of them funnels
 * through {@code ProcessRepository.save}, and the read-model event is emitted there, exactly once,
 * by diffing the status about to be written against the one currently persisted. No write site can
 * forget it, because no write site does it.
 *
 * <p>Always injectable, so the repository calls {@code announceIfChanged} unconditionally right
 * before it persists; when the read model is off it is a single boolean check and no event, so a
 * deployment that does not run the projector pays nothing (no extra read, no extra outbox row, no
 * extra dispatch) on the throughput-critical path. The event rides the same per-process outbox as
 * every other domain event, so it is delivered exactly where the process is owned — dispatched
 * in-process on one database, or relayed to Kafka for an out-of-process projector across shards.
 */
@Component
public class ProcessStatusAnnouncer {

    private final boolean enabled;
    private final String shardId;

    public ProcessStatusAnnouncer(
            @Value("${workflow.projection.enabled:false}") boolean enabled,
            @Value("${workflow.shard-id:}") String shardId) {
        this.enabled = enabled;
        // Blank (the default, non-sharded) reads as null on the event and the index row.
        this.shardId = (shardId == null || shardId.isBlank()) ? null : shardId;
    }

    /** Whether the read model is on. Lets a repository skip the prior-state read entirely when off. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Queues a {@link ProcessStatusChanged} on the process (the caller's save then persists and
     * dispatches it) when the read model is on and this save actually changes the process's status.
     * A brand-new process has {@code previousStatus == null} and is always announced, seeding the
     * index at birth. Re-saves that leave the status untouched (a progress recompute that stays
     * RUNNING, a pausedAt-only touch) emit nothing.
     *
     * @param previousStatus the status currently persisted, or {@code null} if the process is new.
     */
    public void announceIfChanged(Process process, ProcessStatus previousStatus) {
        if (!enabled || previousStatus == process.getStatus()) {
            return;
        }
        process.send(new ProcessStatusChanged(
                process.getId(),
                process.getBusinessKey(),
                process.getWorkflowDefinitionId(),
                process.getWorkflowDefinitionVersion(),
                process.getStatus() == null ? null : process.getStatus().name(),
                process.getCompletionPercentage(),
                process.getCreated(),
                process.getStarted(),
                process.getFinished(),
                // Stamp the emit time here, in causal order — this, not the projector's consume time,
                // is what orders the read model (see ProcessStatusChanged#occurredAt).
                java.time.LocalDateTime.now(),
                // The owning shard, stamped here rather than read by the projector, so a fanned-out
                // projector still records where the process lives (see ProcessStatusChanged#shardId).
                shardId));
    }
}
