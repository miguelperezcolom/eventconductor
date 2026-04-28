package io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.stepexecution.checktimeout.CheckTimeoutCommand;
import io.mateu.workflow.application.usecases.stepexecution.checktimeout.CheckTimeoutUseCase;
import io.mateu.workflow.domain.aggregates.Step;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
public class TriggerTimeoutChecksUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final CheckTimeoutUseCase checkTimeoutUseCase;

    public void handle(TriggerTimeoutChecksCommand command) {
        var now = LocalDateTime.now();
        stepExecutionRepository.findPendingOrRunning().stream()
                .filter(se -> se.getStartedAt() != null)
                .filter(se -> {
                    var step = pojoFromJson(se.getStepJson(), Step.class);
                    return step.timeout() > 0
                            && se.getStartedAt().plus(step.timeout(), ChronoUnit.MILLIS).isBefore(now);
                })
                .forEach(se -> checkTimeoutUseCase.handle(new CheckTimeoutCommand(se.id())));
    }

}
