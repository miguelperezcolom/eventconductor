package io.mateu.workflow.application.usecases.process.pause;

import io.mateu.workflow.application.out.LogMessageRepository;
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

    public void handle(PauseProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (!ProcessStatus.PENDING.equals(process.getStatus())
                && !ProcessStatus.RUNNING.equals(process.getStatus())) {
            log.warn("Process {} cannot be paused from status {} — ignoring",
                    process.getId(), process.getStatus());
            return;
        }

        var paused = process.withStatus(ProcessStatus.PAUSED).withPausedAt(LocalDateTime.now());
        processRepository.save(paused);

        logMessageRepository.save(new LogMessage(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                process.getId(),
                null,
                MessageType.Info.name(),
                "Process paused",
                "system"
        ));
    }
}
