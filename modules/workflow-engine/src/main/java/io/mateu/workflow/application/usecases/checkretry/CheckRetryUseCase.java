package io.mateu.workflow.application.usecases.checkretry;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Wakes the steps of one process whose retry backoff has elapsed. The timeout scheduler found the
 * process (a due AWAITING_RETRY step) and fans out to here; this releases each due retry back to
 * CREATED and steps the process over once, which re-dispatches them.
 *
 * <p>Idempotent and safe to redeliver: a step already released (CREATED) is no longer AWAITING_RETRY
 * so it is not found again, and step-over only dispatches steps whose preconditions still hold.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckRetryUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;

    public void handle(CheckRetryCommand command) {
        var now = LocalDateTime.now();
        var due = stepExecutionRepository.findDueRetriesByProcessId(command.processId(), now);
        if (due.isEmpty()) {
            return;
        }
        // A paused process freezes its retry clocks, exactly like its timers and timeouts: nothing
        // is re-dispatched until it resumes. The deadline stays in the past, so the next scan after
        // resume picks it up immediately.
        if (isPaused(command.processId())) {
            return;
        }
        due.forEach(stepExecution -> {
            stepExecution.releaseForRetry();
            stepExecutionRepository.save(stepExecution);
        });
        stepOverProcessUseCase.handle(new StepOverProcessCommand(command.processId()));
    }

    private boolean isPaused(String processId) {
        return processRepository.findById(processId)
                .map(process -> ProcessStatus.PAUSED.equals(process.getStatus()))
                .orElse(false);
    }
}
