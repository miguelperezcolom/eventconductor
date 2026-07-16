package io.mateu.workflow.application.usecases.stepexecution.retry;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetryStepExecutionUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final WorkflowMetrics workflowMetrics;

    public void handle(RetryStepExecutionCommand command) {
        var stepExecution = stepExecutionRepository.findById(command.stepExecutionId()).orElseThrow();

        if (!StepExecutionStatus.ERROR.equals(stepExecution.getStatus())
                && !StepExecutionStatus.TIMEOUT.equals(stepExecution.getStatus())) {
            return;
        }

        var process = processRepository.findById(stepExecution.getProcessId()).orElseThrow();
        if (ProcessStatus.CANCELLED.equals(process.getStatus())) {
            // Retrying a step must not revive a cancelled process (its other steps
            // are already CANCELLED and will never run).
            return;
        }

        stepExecution.updateStatus(StepExecutionStatus.CREATED);
        stepExecutionRepository.save(stepExecution);
        workflowMetrics.retryPerformed(stepExecution.getWorkflowDefinitionId(),
                WorkflowMetrics.RetryTrigger.MANUAL);

        processRepository.save(process.withStatus(ProcessStatus.RUNNING));
        stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
    }
}
