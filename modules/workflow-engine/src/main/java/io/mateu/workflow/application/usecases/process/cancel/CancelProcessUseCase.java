package io.mateu.workflow.application.usecases.process.cancel;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CancelProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final StreamBridge streamBridge;

    public void handle(CancelProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        
        var stepExecutions = stepExecutionRepository.findByProcess(process);
        boolean changed = false;
        for (var stepExecution : stepExecutions) {
            if (!StepExecutionStatus.ERROR.equals(stepExecution.getStatus()) && !StepExecutionStatus.COMPLETED.equals(stepExecution.getStatus())) {
                if (StepExecutionStatus.PENDING.equals(stepExecution.getStatus()) || StepExecutionStatus.RUNNING.equals(stepExecution.getStatus())) {
                    streamBridge.send("downstream", new TaskCancellationRequested(stepExecution.getId()));
                }
                stepExecution.updateStatus(StepExecutionStatus.CANCELLED);
                stepExecutionRepository.save(stepExecution);
                changed = true;
            }
        }

        if (changed) {
            process = process.withStatus(ProcessStatus.CANCELLED);
            processRepository.save(process);
        }
    }
}
