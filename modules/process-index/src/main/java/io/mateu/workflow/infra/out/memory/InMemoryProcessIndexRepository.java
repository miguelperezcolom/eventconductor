package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Process-index read store for memory persistence — the same projector fills it, in a heap map. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProcessIndexRepository implements ProcessIndexRepository {

    private final ConcurrentHashMap<String, ProcessIndexRow> byProcessId = new ConcurrentHashMap<>();

    @Override
    public void upsert(ProcessIndexRow row) {
        // Last-write-wins by the projector; keep the newer updatedAt if events arrive out of order.
        byProcessId.merge(row.processId(), row,
                (existing, incoming) ->
                        incoming.updatedAt() == null || existing.updatedAt() == null
                                || !incoming.updatedAt().isBefore(existing.updatedAt())
                                ? incoming : existing);
    }

    @Override
    public List<ProcessIndexRow> findByStatusIn(Collection<String> statuses) {
        return byProcessId.values().stream()
                .filter(r -> statuses.contains(r.status()))
                .toList();
    }

    @Override
    public List<ProcessIndexRow> findByWorkflowDefinitionIdAndStatusIn(String workflowDefinitionId,
                                                                       Collection<String> statuses) {
        return byProcessId.values().stream()
                .filter(r -> workflowDefinitionId.equals(r.workflowDefinitionId()))
                .filter(r -> statuses.contains(r.status()))
                .toList();
    }

    @Override
    public Optional<ProcessIndexRow> findByBusinessKey(String businessKey) {
        return byProcessId.values().stream()
                .filter(r -> businessKey.equals(r.businessKey()))
                .findFirst();
    }

    @Override
    public Optional<ProcessIndexRow> findByProcessId(String processId) {
        return Optional.ofNullable(byProcessId.get(processId));
    }

    @Override
    public Map<String, Long> countByStatus() {
        return byProcessId.values().stream()
                .collect(Collectors.groupingBy(ProcessIndexRow::status, Collectors.counting()));
    }
}
