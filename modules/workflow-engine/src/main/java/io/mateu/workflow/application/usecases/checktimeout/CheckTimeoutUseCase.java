package io.mateu.workflow.application.usecases.checktimeout;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.checktimeout.checksteptimeout.CheckStepTimeoutCommand;
import io.mateu.workflow.application.usecases.checktimeout.checksteptimeout.CheckStepTimeoutHandler;
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
    final CheckStepTimeoutHandler checkTimeoutUseCase;

    public void handle(CheckTimeoutCommand command) {
        var now = LocalDateTime.now();
        stepExecutionRepository.findPendingOrRunning().stream()
                .filter(se -> se.getProcessId().equals(command.processId()))
                .filter(se -> se.getStartedAt() != null)
                .filter(se -> {
                    var step = pojoFromJson(se.getStepJson(), Step.class);
                    return step.timeout() > 0
                            && se.getStartedAt().plus(step.timeout(), ChronoUnit.MILLIS).isBefore(now);
                })
                .forEach(se -> checkTimeoutUseCase.handle(new CheckStepTimeoutCommand(se.id())));
    }

}
