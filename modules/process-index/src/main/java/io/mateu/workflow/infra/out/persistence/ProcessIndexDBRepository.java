package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.paging.ServedPage;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
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
public class ProcessIndexDBRepository implements ProcessIndexRepository {

    private final ProcessIndexEntityRepository repository;

    public ProcessIndexDBRepository(ProcessIndexEntityRepository repository) {
        this.repository = repository;
    }

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
                row.processId(), row.businessKey(), row.name(), row.workflowDefinitionId(),
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
    public Optional<ProcessIndexRow> findByProcessId(String processId) {
        return repository.findById(processId).map(this::toRow);
    }

    @Override
    public Map<String, Long> countByStatus() {
        return repository.countGroupedByStatus().stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));
    }

    /**
     * Counted first, then the page — which page can be served depends on how many there are, and
     * the listing's contract is that a request past the end is answered with the last real page.
     */
    @Override
    public java.util.Optional<ProcessIndexPage> search(String searchText, boolean onlyErrors,
                                                       int page, int size) {
        var pattern = (searchText == null || searchText.isBlank())
                ? null : "%" + searchText.toLowerCase() + "%";
        var total = repository.countSearch(onlyErrors, pattern);
        var served = ServedPage.of(page, size, total);
        var content = repository
                .search(onlyErrors, pattern,
                        org.springframework.data.domain.PageRequest.of(served.number(), served.size()))
                .stream().map(this::toRow).toList();
        return java.util.Optional.of(
                new ProcessIndexPage(content, total, served.number(), served.size()));
    }

    private ProcessIndexRow toRow(ProcessIndexEntity e) {
        return new ProcessIndexRow(
                e.getProcessId(), e.getBusinessKey(), e.getName(), e.getWorkflowDefinitionId(),
                e.getWorkflowDefinitionVersion(), e.getStatus(), e.getCompletionPercentage(),
                e.getCreated(), e.getStarted(), e.getFinished(), e.getUpdatedAt(), e.getShardId());
    }
}
