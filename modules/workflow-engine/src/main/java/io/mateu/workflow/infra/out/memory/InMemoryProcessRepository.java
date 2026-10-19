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

    /**
     * Process ids by business key — every process that carries the key, holder or released. Every
     * creation looks its key up and every save checks that no second process holds it, and both
     * used to scan the whole store, so each process cost more than the last: five batches of 20,000
     * in the benchmark suite fell from ~5,000 to ~560 processes/s. Reading the few processes under
     * one key gives the same answers. An id is indexed before its process is stored and unindexed
     * after it is removed, so a reader can meet an id with no process (skipped) but never a process
     * it cannot find; {@code compute} keeps a set emptied by one thread from swallowing another's add.
     */
    private final Map<String, java.util.Set<String>> idsByBusinessKey = new ConcurrentHashMap<>();

    private void indexKey(String key, String id) {
        if (key == null) {
            return;
        }
        idsByBusinessKey.compute(key, (k, ids) -> {
            var set = ids == null ? ConcurrentHashMap.<String>newKeySet() : ids;
            set.add(id);
            return set;
        });
    }

    private void unindexKey(String key, String id) {
        if (key == null) {
            return;
        }
        idsByBusinessKey.computeIfPresent(key, (k, ids) -> {
            ids.remove(id);
            return ids.isEmpty() ? null : ids;
        });
    }

    /** The processes carrying {@code key}, from the index — what a scan of the store would find. */
    private java.util.stream.Stream<Process> withKey(String key) {
        var ids = idsByBusinessKey.get(key);
        if (ids == null) {
            return java.util.stream.Stream.empty();
        }
        return ids.stream().map(store::get).filter(java.util.Objects::nonNull)
                .filter(p -> key.equals(p.getBusinessKey()));
    }

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
        refuseASecondHolder(process);
        indexKey(process.getBusinessKey(), process.id());
        var previous = store.put(process.id(), process);
        if (previous != null && previous.getBusinessKey() != null
                && !previous.getBusinessKey().equals(process.getBusinessKey())) {
            unindexKey(previous.getBusinessKey(), process.id());
        }
        if (process.hasReplied() && replySignal != null) {
            replySignal.ifAvailable(signal -> signal.replyRecorded(process.id()));
        }
        process.popEvents().forEach(event ->
                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event)));
        return process.id();
    }

    /**
     * The database's UNIQUE {@code business_key}, here: a process may hold a key only while no other process holds it
     * (one that handed it over — {@code businessKeyReleased} — no longer does). Without it the memory store would
     * quietly keep two holders where JPA refuses the second, and a lookup by key would pick one of them.
     */
    private void refuseASecondHolder(Process process) {
        var key = process.getBusinessKey();
        if (key == null || key.isBlank() || process.isBusinessKeyReleased()) {
            return;
        }
        synchronized (store) {
            withKey(key)
                    .filter(other -> !other.id().equals(process.id()) && !other.isBusinessKeyReleased())
                    .findAny()
                    .ifPresent(other -> {
                        throw new org.springframework.dao.DuplicateKeyException("Business key '" + key
                                + "' is already held by process " + other.id());
                    });
        }
    }

    @Override
    public List<Process> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(id -> {
            var removed = store.remove(id);
            if (removed != null && removed.getBusinessKey() != null) {
                unindexKey(removed.getBusinessKey(), id);
            }
        });
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
        if (businessKey == null) {
            return Optional.empty();
        }
        // The run that holds it: one at a time, as the database's UNIQUE column has it.
        return withKey(businessKey)
                .filter(p -> !p.isBusinessKeyReleased())
                .max(Comparator.comparing(Process::getCreated, Comparator.nullsFirst(Comparator.naturalOrder())));
    }
}
