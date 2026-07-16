package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepType;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Single scan over live step executions shared by both scheduler variants (memory and JPA),
 * classifying the processes that need attention: steps whose {@code timeout} deadline has
 * expired, and TIMER steps whose due moment has passed. Only expired deadlines are reported —
 * publishing a check per live step would flood the topic on busy systems.
 */
class StepDeadlines {

    record Result(Set<String> timedOutProcessIds, Set<String> dueTimerProcessIds) {
    }

    static Result scan(Collection<StepExecution> pendingOrRunning, LocalDateTime now) {
        var timedOut = new LinkedHashSet<String>();
        var dueTimers = new LinkedHashSet<String>();
        for (var stepExecution : pendingOrRunning) {
            if (stepExecution.getStartedAt() == null) {
                continue;
            }
            var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
            if (StepType.TIMER.equals(step.type())) {
                if (isTimerDue(step, stepExecution, now)) {
                    dueTimers.add(stepExecution.getProcessId());
                }
            } else if (step.timeout() > 0
                    && stepExecution.getStartedAt().plus(step.timeout(), ChronoUnit.MILLIS).isBefore(now)) {
                timedOut.add(stepExecution.getProcessId());
            }
        }
        return new Result(timedOut, dueTimers);
    }

    private static boolean isTimerDue(Step step, StepExecution stepExecution, LocalDateTime now) {
        try {
            return !step.timerDueAt(stepExecution.getStartedAt(), stepExecution.getVariables()).isAfter(now);
        } catch (IllegalArgumentException e) {
            // A misconfigured timer already failed at start(); nothing to fire here.
            return false;
        }
    }

    private StepDeadlines() {
    }
}
