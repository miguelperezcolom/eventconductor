package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexProjection;
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
 * where the index lives in the same database. The same projection ({@code ProcessIndexProjection}) is
 * what the standalone projector runs when it consumes the shared projection topic across sharded
 * databases; the event carries the whole projected shape precisely so it needs no access to the write
 * side.
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
        // The projection itself lives in ProcessIndexProjection, shared with the standalone
        // projector: this handler is the engine's *host* for it (domain-event dispatch, in-process,
        // same database), not a second copy of the rules.
        ProcessIndexProjection.apply(processIndexRepository, e);
    }
}
