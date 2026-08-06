package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Process-index read store on the relational database (jpa persistence). */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class ProcessIndexDBRepository implements ProcessIndexRepository {

    private final ProcessIndexEntityRepository repository;

    @Override
    public void upsert(ProcessIndexRow row) {
        // save() is an upsert on the @Id (processId). Out-of-order redelivery is guarded: skip a
        // write whose event is older than what is already stored.
        var existing = repository.findById(row.processId()).orElse(null);
        if (existing != null && existing.getUpdatedAt() != null && row.updatedAt() != null
                && row.updatedAt().isBefore(existing.getUpdatedAt())) {
            return;
        }
        repository.save(new ProcessIndexEntity(
                row.processId(), row.businessKey(), row.workflowDefinitionId(),
                row.workflowDefinitionVersion(), row.status(), row.completionPercentage(),
                row.created(), row.started(), row.finished(), row.updatedAt(), row.shardId()));
    }

    @Override
    public List<ProcessIndexRow> findByStatusIn(Collection<String> statuses) {
        return repository.findAllByStatusIn(List.copyOf(statuses)).stream().map(this::toRow).toList();
    }

    @Override
    public List<ProcessIndexRow> findByWorkflowDefinitionIdAndStatusIn(String workflowDefinitionId,
                                                                       Collection<String> statuses) {
        return repository.findAllByWorkflowDefinitionIdAndStatusIn(workflowDefinitionId, List.copyOf(statuses))
                .stream().map(this::toRow).toList();
    }

    @Override
    public Optional<ProcessIndexRow> findByBusinessKey(String businessKey) {
        return repository.findFirstByBusinessKey(businessKey).map(this::toRow);
    }

    @Override
    public Map<String, Long> countByStatus() {
        return repository.findAll().stream()
                .collect(Collectors.groupingBy(ProcessIndexEntity::getStatus, Collectors.counting()));
    }

    private ProcessIndexRow toRow(ProcessIndexEntity e) {
        return new ProcessIndexRow(
                e.getProcessId(), e.getBusinessKey(), e.getWorkflowDefinitionId(),
                e.getWorkflowDefinitionVersion(), e.getStatus(), e.getCompletionPercentage(),
                e.getCreated(), e.getStarted(), e.getFinished(), e.getUpdatedAt(), e.getShardId());
    }
}
