package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The CQRS projector: turns {@link ProcessStatusChanged} into an upsert of the process-index read
 * model. Present only when {@code workflow.projection.enabled} is on — and events are emitted only
 * then too (see {@code ProcessStatusAnnouncer}), so the whole read model is opt-in and costs a
 * disabled deployment nothing.
 *
 * <p>Runs in-process here, sharing the engine's domain-event dispatch — the non-sharded default,
 * where the index lives in the same database. The same projection (this upsert of one event) is what
 * a standalone projector runs when it consumes the domain-event topics across sharded databases; the
 * event carries the whole projected shape precisely so it needs no access to the write side.
 */
@Service
@ConditionalOnProperty(name = "workflow.projection.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProcessStatusProjectionHandler implements DomainEventHandler<ProcessStatusChanged> {

    private final ProcessIndexRepository processIndexRepository;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return ProcessStatusChanged.class;
    }

    @Override
    public void handle(ProcessStatusChanged e) {
        // Order by the event's emit time, not now(): a single node can dispatch a freshly-created
        // process's events out of causal order (its creation cascade completes before the creation's
        // own seed is dispatched), which a consume-time stamp would let clobber the final state. The
        // owning shard likewise rides the event (stamped on that shard), so a fanned-out projector
        // records where the process lives rather than its own shard id.
        processIndexRepository.upsert(ProcessIndexRow.from(e, e.occurredAt(), e.shardId()));
    }
}
