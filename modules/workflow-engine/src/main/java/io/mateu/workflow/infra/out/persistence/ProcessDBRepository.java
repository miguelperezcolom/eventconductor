package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class ProcessDBRepository implements ProcessRepository {

    final ProcessEntityRepository processEntityRepository;
    final OutboxMessageEntityRepository outboxMessageEntityRepository;

    @Override
    public Optional<Process> findById(String id) {
        return processEntityRepository.findById(id)
                .map(this::map);
    }

    private Process map(ProcessEntity entity) {
        return new Process(
                entity.getId(),
                entity.getName(),
                entity.getWorkflowDefinitionId(),
                entity.getWorkflowDefinitionVersion(),
                entity.getWorkflowDefinitionJson(),
                entity.getBusinessKey(),
                listFromJson(entity.getVariables(), Variable.class),
                ProcessStatus.valueOf(entity.getStatus()),
                entity.getCompletionPercentage(),
                entity.getCreated(),
                entity.getStarted(),
                entity.getFinished()
        );
    }

    @Override
    public String save(Process process) {
        // Normalize empty businessKey to null so the unique constraint does not
        // reject multiple processes that have no business key.
        var businessKey = (process.getBusinessKey() == null || process.getBusinessKey().isBlank())
                ? null : process.getBusinessKey();
        processEntityRepository.save(new ProcessEntity(
                process.getId(),
                businessKey,
                process.getName(),
                toJson(process.getVariables()),
                process.getStatus().name(),
                process.getCompletionPercentage(),
                "log",
                process.getWorkflowDefinitionId(),
                process.getWorkflowDefinitionVersion(),
                process.getWorkflowDefinitionJson(),
                process.getCreated(),
                process.getStarted(),
                process.getFinished()
        ));
        process.popEvents().stream()
                .map(OutboxMessageEntity::new)
                .forEach(outboxMessageEntityRepository::save);
        return process.getId();
    }

    @Override
    public List<Process> findAll() {
        return processEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        processEntityRepository.deleteAllById(selectedIds);
    }

    @Override
    public Optional<Process> findByBusinessKey(String businessKey) {
        return processEntityRepository.findByBusinessKey(businessKey)
                .map(this::map);
    }

    @Override
    public long countByStatus(ProcessStatus status) {
        return processEntityRepository.countByStatus(status.name());
    }
}
