package io.mateu.workflow.application.usecases.process.pause;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Pauses a PENDING or RUNNING process. A paused process holds new step dispatches (the
 * orchestration gate in {@code WorkflowOrchestrationService}) and freezes step clocks
 * (timeout and timer schedulers skip it), but in-flight work is not cancelled: worker
 * reports and correlated messages are still accepted — only successors are held until
 * {@code ResumeProcessUseCase} runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PauseProcessUseCase {

    final ProcessRepository processRepository;
    final LogMessageRepository logMessageRepository;
    final ProcessLockService processLockService;

    public void handle(PauseProcessCommand command) {
        // Under the process lock, like every other writer of the process: the status check and the
        // writes that follow it are one unit, so a pause cannot interleave with the relay moving the
        // process, nor overwrite a status another writer has just changed. A lock that cannot be had
        // fails rather than doing nothing silently — the requested event is redelivered.
        ProcessLockService.runExclusivelyOrThrow(processLockService, command.processId(), () -> doHandle(command));
    }

    /**
     * Whether a process in {@code status} can be paused: PENDING or RUNNING. Public so a door that
     * answers synchronously (the REST API) refuses with the same rule the use case applies.
     */
    public static boolean acceptsFrom(ProcessStatus status) {
        return ProcessStatus.PENDING.equals(status) || ProcessStatus.RUNNING.equals(status);
    }

    private void doHandle(PauseProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (!acceptsFrom(process.getStatus())) {
            log.warn("Process {} cannot be paused from status {} — ignoring",
                    process.getId(), process.getStatus());
            return;
        }

        var paused = process.withStatus(ProcessStatus.PAUSED).withPausedAt(EngineClock.now());
        processRepository.save(paused);

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                EngineClock.now(),
                process.getId(),
                null,
                MessageType.Info.name(),
                "Process paused",
                "system"
        ));
    }
}
