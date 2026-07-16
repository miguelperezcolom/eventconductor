package io.mateu.workflow.application.usecases.checktimer;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.checktimer.completetimerstep.CompleteTimerStepCommand;
import io.mateu.workflow.application.usecases.checktimer.completetimerstep.CompleteTimerStepHandler;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckTimerUseCase {

    final StepExecutionRepository stepExecutionRepository;
    final CompleteTimerStepHandler completeTimerStepHandler;

    public void handle(CheckTimerCommand command) {
        var now = LocalDateTime.now();
        stepExecutionRepository.findPendingOrRunning().stream()
                .filter(se -> se.getProcessId().equals(command.processId()))
                .filter(se -> se.getStartedAt() != null)
                .filter(se -> isDue(se, now))
                .forEach(se -> completeTimerStepHandler.handle(new CompleteTimerStepCommand(se.id())));
    }

    private boolean isDue(StepExecution stepExecution, LocalDateTime now) {
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        if (!StepType.TIMER.equals(step.type())) {
            return false;
        }
        try {
            return !step.timerDueAt(stepExecution.getStartedAt(), stepExecution.getVariables()).isAfter(now);
        } catch (IllegalArgumentException e) {
            // A misconfigured timer already failed at start(); nothing to fire here.
            return false;
        }
    }

}
