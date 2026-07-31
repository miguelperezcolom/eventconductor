package io.mateu.workflow.application.usecases.checktimeout;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.checktimeout.checksteptimeout.CheckStepTimeoutCommand;
import io.mateu.workflow.application.usecases.checktimeout.checksteptimeout.CheckStepTimeoutHandler;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
public class CheckTimeoutUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final CheckStepTimeoutHandler checkTimeoutUseCase;

    public void handle(CheckTimeoutCommand command) {
        var now = LocalDateTime.now();
        var expired = stepExecutionRepository.findPendingOrRunning().stream()
                .filter(se -> se.getProcessId().equals(command.processId()))
                .filter(se -> se.getStartedAt() != null)
                .filter(se -> {
                    var step = pojoFromJson(se.getStepJson(), Step.class);
                    return step.timeout() > 0
                            && se.getStartedAt().plus(step.timeout(), ChronoUnit.MILLIS).isBefore(now);
                })
                .toList();
        if (expired.isEmpty()) {
            return;
        }
        // A paused process freezes its step clocks: nothing may time out until it is resumed
        // (on resume the startedAt shift pushes the deadlines forward by the pause duration).
        // The process is only looked up when a step would otherwise fire.
        if (isPaused(command.processId())) {
            return;
        }
        expired.forEach(se -> checkTimeoutUseCase.handle(new CheckStepTimeoutCommand(se.id())));
    }

    private boolean isPaused(String processId) {
        return processRepository.findById(processId)
                .map(process -> ProcessStatus.PAUSED.equals(process.getStatus()))
                .orElse(false);
    }

}
