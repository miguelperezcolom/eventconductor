package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class StepExecutionDBRepository implements StepExecutionRepository {

    final StreamBridge streamBridge;
    final StepExecutionEntityRepository stepExecutionEntityRepository;

    @Override
    public Optional<StepExecution> findById(String id) {
        return stepExecutionEntityRepository.findById(id).map(this::map);
    }

    private StepExecution map(StepExecutionEntity entity) {
        return new StepExecution(
                entity.getId(),
                entity.getProcessId(),
                entity.getWorkflowDefinitionId(),
                entity.getStepId(),
                entity.getStepJson(),
                listFromJson(entity.getVariables(), Variable.class),
                StepExecutionStatus.valueOf(entity.getStatus()),
                entity.getWorkerId()
        );
    }

    @Override
    public String save(StepExecution stepExecution) {
        stepExecutionEntityRepository.save(new StepExecutionEntity(
            stepExecution.id(),
                stepExecution.getProcessId(),
                stepExecution.getWorkflowDefinitionId(),
                stepExecution.getStepId(),
                stepExecution.getStepJson(),
                toJson(stepExecution.getVariables()),
                stepExecution.getStatus().name(),
                stepExecution.getWorkerId()
        ));
        return stepExecution.id();
    }

    @Override
    public List<StepExecution> findAll() {
        return stepExecutionEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        stepExecutionEntityRepository.deleteAllById(selectedIds);
    }

    @Override
    public List<StepExecution> findByProcess(Process process) {
        return stepExecutionEntityRepository.findAllByProcessId(process.id());
    }
}
