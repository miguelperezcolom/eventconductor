package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreateProcessUseCase {

    final ProcessRepository processRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final StepExecutionRepository stepExecutionRepository;

    public void handle(CreateProcessCommand command) {
        // Idempotency: if a non-empty businessKey is provided and a process already
        // exists for it, this is a duplicate event — skip silently.
        if (command.processId() != null && !command.processId().isBlank()) {
            if (processRepository.findById(command.processId()).isPresent()) {
                log.warn("Process with process Id '{}' already exists, ignoring duplicate creation request",
                        command.processId());
                return;
            }
        }

        var workflowDefinition = workflowDefinitionRepository.findById(command.workflowDefinitionId()).orElseThrow();
        AtomicInteger position = new AtomicInteger(1);
        var stepExecutions = workflowDefinition.steps().stream()
                .map(step -> StepExecution.create(step, command.processId(), position.getAndIncrement())).toList();

        stepExecutions.forEach(stepExecutionRepository::save);

        var process = Process
                .create(
                        command.processId(),
                        workflowDefinition,
                        command.businessKey(),
                        command.variables() != null?command.variables(): List.of()
                );
        processRepository.save(process);

        // enviar evento proceso creado (para step over)


    }

}
