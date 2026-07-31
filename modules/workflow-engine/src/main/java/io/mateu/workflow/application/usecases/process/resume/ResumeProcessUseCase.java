package io.mateu.workflow.application.usecases.process.resume;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessStepExecutionUpdateCommand;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Resumes a PAUSED process. Before letting the flow move again, the clocks of every in-flight
 * step are shifted forward by the pause duration: {@code timerDueAt} and step timeouts both
 * derive from {@code startedAt}, so shifting it freezes TIMER due moments and timeout
 * deadlines for exactly as long as the process was paused. Then the process goes back to
 * RUNNING and the flow is driven forward (successors held during the pause start now,
 * deferred blocking errors engage, an already-finished flow completes).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final LogMessageRepository logMessageRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final ProcessUpdateStepExecutionUpdateUseCase processUpdateStepExecutionUpdateUseCase;

    public void handle(ResumeProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (!ProcessStatus.PAUSED.equals(process.getStatus())) {
            log.warn("Process {} cannot be resumed from status {} — ignoring",
                    process.getId(), process.getStatus());
            return;
        }

        var pauseDuration = process.getPausedAt() == null
                ? Duration.ZERO
                : Duration.between(process.getPausedAt(), LocalDateTime.now());

        // Shift the in-flight step clocks BEFORE the process becomes RUNNING again, so a
        // concurrent scheduler scan cannot fire a timer/timeout in between with the stale
        // (pre-pause) startedAt.
        for (var stepExecution : stepExecutionRepository.findByProcess(process)) {
            if (!stepExecution.getStatus().isTerminal() && stepExecution.getStartedAt() != null) {
                stepExecutionRepository.save(
                        stepExecution.withStartedAt(stepExecution.getStartedAt().plus(pauseDuration)));
            }
        }

        var resumed = process.withStatus(ProcessStatus.RUNNING).withPausedAt(null);
        processRepository.save(resumed);

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                process.getId(),
                null,
                MessageType.Info.name(),
                "Process resumed after " + pauseDuration,
                "system"
        ));

        // Drive the flow forward: successors of steps that completed during the pause start
        // now, and the process status/completion is recomputed from its steps.
        stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        processUpdateStepExecutionUpdateUseCase.handle(new ProcessStepExecutionUpdateCommand(process.getId()));
    }
}
