package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class CreateProcessUseCase {

    final OutboxMessageEntityRepository outboxMessageEntityRepository;
    final ProcessRepository processRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final StepExecutionRepository stepExecutionRepository;

    public void handle(CreateProcessCommand command) {
        // crear y grabar proceso
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
