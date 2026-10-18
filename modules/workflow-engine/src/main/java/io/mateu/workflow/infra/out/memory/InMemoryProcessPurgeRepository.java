package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessPurgeRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessTraceContextRepository;
import io.mateu.workflow.application.out.ResourceRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessRetention;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The retention purge over the heap stores of memory mode — the same rules as the JPA store
 * ({@code JdbcProcessPurgeRepository}), so a long-running embedded engine does not grow without bound
 * either. One JVM: the checks and the removal run under this repository's monitor, and the process
 * statuses are read again right before anything is removed.
 */
@Repository
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@RequiredArgsConstructor
public class InMemoryProcessPurgeRepository implements ProcessPurgeRepository {

    final ProcessRepository processes;
    final StepExecutionRepository stepExecutions;
    final LogMessageRepository logMessages;
    final ResourceRepository resources;
    final ProcessTraceContextRepository traceContexts;
    final InMemoryInvocationRepository invocations;
    final InMemoryLockService locks;

    @Override
    public Set<String> knownWorkflowDefinitionIds() {
        var ids = new LinkedHashSet<String>();
        processes.findAll().forEach(p -> {
            if (p.getWorkflowDefinitionId() != null) {
                ids.add(p.getWorkflowDefinitionId());
            }
        });
        return ids;
    }

    @Override
    public List<PurgeCandidate> findRoots(String workflowDefinitionId, ProcessStatus status,
                                          LocalDateTime finishedBefore, PurgeCandidate after, int limit) {
        Comparator<PurgeCandidate> order = Comparator.comparing(PurgeCandidate::finished).thenComparing(PurgeCandidate::id);
        return processes.findAll().stream()
                .filter(p -> workflowDefinitionId.equals(p.getWorkflowDefinitionId()) && status == p.getStatus())
                .filter(p -> p.getFinished() != null && p.getFinished().isBefore(finishedBefore))
                .filter(this::isRoot)
                .map(InMemoryProcessPurgeRepository::candidate)
                .filter(c -> after == null || order.compare(c, after) > 0)
                .sorted(order)
                .limit(limit)
                .toList();
    }

    @Override
    public List<PurgeCandidate> findChildren(Collection<String> processIds) {
        var parents = new HashSet<>(processIds);
        return processes.findAll().stream()
                .filter(p -> p.getParentStepExecutionId() != null)
                .filter(p -> stepExecutions.findById(p.getParentStepExecutionId())
                        .map(StepExecution::getProcessId).filter(parents::contains).isPresent())
                .map(InMemoryProcessPurgeRepository::candidate)
                .toList();
    }

    @Override
    public synchronized boolean purge(List<PurgeCandidate> tree) {
        var ids = tree.stream().map(PurgeCandidate::id).distinct().toList();
        if (ids.isEmpty()) {
            return false;
        }
        for (var id : ids) {
            var process = processes.findById(id).orElse(null);
            if (process == null || !ProcessRetention.isTerminal(process.getStatus()) || process.getFinished() == null) {
                return false;
            }
        }
        var steps = ids.stream().flatMap(id -> stepExecutions.findByProcessId(id).stream()).toList();
        if (steps.stream().anyMatch(s -> s.getStatus() != null && s.getStatus().keepsTheProcessInFlight())
                || locks.holdsOrAwaitsAny(ids)) {
            return false;
        }
        stepExecutions.deleteAllById(steps.stream().map(StepExecution::getId).toList());
        logMessages.deleteAllById(ids.stream().flatMap(id -> logMessages.findByProcessId(id).stream())
                .map(m -> m.id()).filter(Objects::nonNull).toList());
        resources.deleteAllById(ids.stream().flatMap(id -> resources.findByProcessId(id).stream())
                .map(r -> r.id()).filter(Objects::nonNull).toList());
        traceContexts.deleteAllByProcessId(ids);
        invocations.deleteByProcessIds(ids);
        processes.deleteAllById(ids);
        return true;
    }

    private boolean isRoot(Process process) {
        return process.getParentStepExecutionId() == null
                || stepExecutions.findById(process.getParentStepExecutionId()).isEmpty();
    }

    private static PurgeCandidate candidate(Process process) {
        return new PurgeCandidate(process.getId(), process.getWorkflowDefinitionId(), process.getStatus(),
                process.getFinished());
    }
}
