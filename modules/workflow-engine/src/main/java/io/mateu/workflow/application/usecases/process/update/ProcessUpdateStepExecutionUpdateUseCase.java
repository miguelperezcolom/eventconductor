package io.mateu.workflow.application.usecases.process.update;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessUpdateStepExecutionUpdateUseCase {

    final ProcessRepository repository;
    final StepExecutionRepository stepExecutionRepository;

    public void handle(ProcessStepExecutionUpdateCommand command) {
        var process = repository.findById(command.processId()).orElseThrow();
        var executions = stepExecutionRepository.findByProcess(process);
        var status = ProcessStatus.COMPLETED;
        for (StepExecution execution : executions) {
            if (StepExecutionStatus.CREATED == execution.getStatus()) {
                status = ProcessStatus.PENDING;
            }
        }
        for (StepExecution execution : executions) {
            if (StepExecutionStatus.PENDING == execution.getStatus()) {
                status = ProcessStatus.RUNNING;
            }
        }
        for (StepExecution execution : executions) {
            if (StepExecutionStatus.RUNNING == execution.getStatus()) {
                status = ProcessStatus.RUNNING;
            }
        }
        repository.save(process.withStatus(status));
    }

}
