package io.mateu.workflow.application.usecases.process.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProcessUpdateStepExecutionUpdateUseCase {

    final ProcessRepository repository;
    final StepExecutionRepository stepExecutionRepository;

    public void handle(ProcessStepExecutionUpdateCommand command) {
        var process = repository.findById(command.processId()).orElseThrow();
        var executions = stepExecutionRepository.findByProcess(process);
        var status = !executions.isEmpty() ? ProcessStatus.COMPLETED:ProcessStatus.PENDING;
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
        var percent = Math.round(100d * executions.stream().filter(e -> StepExecutionStatus.COMPLETED.equals(e.getStatus())).count() / (double) executions.size());
        if (percent > 0) {
            status = ProcessStatus.RUNNING;
        }
        if (percent == 100) {
            status = ProcessStatus.COMPLETED;
        }
        process = process.withStatus(status).withCompletionPercentage((int) percent);
        if (process.getStarted() == null && (status.equals(ProcessStatus.RUNNING)
                || status.equals(ProcessStatus.COMPLETED)
                || status.equals(ProcessStatus.ERROR))) {
            process = process.withStarted(LocalDateTime.now());
        }
        if (process.getFinished() == null && (status.equals(ProcessStatus.COMPLETED)
                || status.equals(ProcessStatus.ERROR))) {
            process = process.withFinished(LocalDateTime.now());
        }
        repository.save(process);
    }

}
