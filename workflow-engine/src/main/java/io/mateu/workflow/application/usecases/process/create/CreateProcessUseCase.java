package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.shared.AggregateRepository;
import io.mateu.workflow.infra.out.persistence.StepExecutionDBCrudAdapter;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionDBCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateProcessUseCase {

    final AggregateRepository<Process, String> processCrudAdapter;
    final WorkflowDefinitionDBCrudAdapter workflowDefinitionCrudAdapter;
    final StepExecutionDBCrudAdapter stepExecutionCrudAdapter;

    public void handle(CreateProcessCommand command) {
        // crear y grabar proceso
        var workflowDefinition = workflowDefinitionCrudAdapter.findById(command.workflowDefinitionId()).orElseThrow();
        var stepExecutions = workflowDefinition.steps().stream()
                .map(step -> StepExecution.create(step, command.processId()))
                .map(stepExecutionCrudAdapter::save)
                .toList();

        processCrudAdapter.save(Process
                .create(
                        command.processId(),
                        workflowDefinition,
                        command.businessKey(),
                        command.variables(),
                        stepExecutions.stream().map(StepExecution::id).toList()
                ));
        // enviar evento proceso creado (para step over)
    }

}
