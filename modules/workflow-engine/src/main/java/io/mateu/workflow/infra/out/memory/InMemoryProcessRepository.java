package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProcessRepository implements ProcessRepository {

    // @Lazy breaks the circular dependency:
    // InMemoryProcessRepository → ProcessDomainEventUseCase → handlers → use cases → InMemoryProcessRepository
    @Lazy
    @Autowired
    private ProcessDomainEventUseCase processDomainEventUseCase;

    @Autowired
    private io.mateu.workflow.application.services.ProcessStatusAnnouncer processStatusAnnouncer;

    // Providers: both read processes back through this repository.
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.sync.EngineReplyPolicy> engineReplyPolicy;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.ReplySignal> replySignal;

    // Provider: only findStalled reads steps, and the step store is a sibling bean of this one.
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.out.StepExecutionRepository> stepExecutionRepository;

    // Computes the search attributes (memory keeps none: the listing evaluates them per process).
    @Autowired(required = false)
    private io.mateu.workflow.application.services.SearchAttributeIndexer searchAttributeIndexer;

    private final Map<String, Process> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Process> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(Process process) {
        // See ProcessDBRepository: the engine's answer for a process that ended without replying.
        if (engineReplyPolicy != null) {
            engineReplyPolicy.ifAvailable(policy -> policy.apply(process));
        }
        // Read-model event, emitted at the one point every status transition funnels through: if the
        // read model is on and this save changes the status, ride a ProcessStatusChanged on the same
        // dispatch below. Off → no store read, no event.
        if (processStatusAnnouncer.isEnabled()) {
            var previous = store.get(process.id());
            processStatusAnnouncer.announceIfChanged(process, previous == null ? null : previous.getStatus(),
                    searchAttributeIndexer == null ? null : searchAttributeIndexer.index(process));
        }
        store.put(process.id(), process);
        if (process.hasReplied() && replySignal != null) {
            replySignal.ifAvailable(signal -> signal.replyRecorded(process.id()));
        }
        process.popEvents().forEach(event ->
                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event)));
        return process.id();
    }

    @Override
    public List<Process> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }

    /** The JPA query's semantics over the heap; see {@code ProcessEntityRepository#findStalled}. */
    @Override
    public List<String> findStalled(LocalDateTime idleBefore, int limit) {
        var steps = stepExecutionRepository == null ? null : stepExecutionRepository.getIfAvailable();
        if (steps == null) {
            return List.of();
        }
        record Candidate(String id, LocalDateTime lastMoved) {
        }
        return store.values().stream()
                .filter(process -> ProcessStatus.RUNNING.equals(process.getStatus()))
                .map(process -> {
                    var executions = steps.findByProcessId(process.id());
                    if (executions.stream().anyMatch(se -> se.getStatus().keepsTheProcessInFlight())) {
                        return null;
                    }
                    var lastMoved = executions.stream()
                            .map(StepExecution::getFinishedAt)
                            .filter(java.util.Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
                    // Never moved is not stalled, as in the query.
                    return lastMoved != null && lastMoved.isBefore(idleBefore)
                            ? new Candidate(process.id(), lastMoved) : null;
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Candidate::lastMoved).reversed())
                .limit(limit)
                .map(Candidate::id)
                .toList();
    }

    @Override
    public Optional<Process> findByBusinessKey(String businessKey) {
        return store.values().stream()
                .filter(p -> businessKey.equals(p.getBusinessKey()))
                .findFirst();
    }
}
