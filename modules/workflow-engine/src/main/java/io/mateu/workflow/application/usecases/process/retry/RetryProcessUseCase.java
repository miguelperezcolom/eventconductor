package io.mateu.workflow.application.usecases.process.retry;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetryProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;

    public void handle(RetryProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        
        var stepExecutions = stepExecutionRepository.findByProcess(process);
        boolean changed = false;
        for (var stepExecution : stepExecutions) {
            if (StepExecutionStatus.ERROR.equals(stepExecution.getStatus())) {
                stepExecution.updateStatus(StepExecutionStatus.CREATED);
                stepExecutionRepository.save(stepExecution);
                changed = true;
            }
        }

        if (changed) {
            process = process.withStatus(ProcessStatus.RUNNING);
            processRepository.save(process);
            stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        }
    }
}
