package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Splits the step executions the repository reported as due into the two kinds of attention they
 * need — an expired {@code timeout} and a TIMER whose moment has come. Shared by both scheduler
 * variants (memory and JPA).
 *
 * <p>Deciding <em>whether</em> a step is due no longer happens here: the deadline is materialised
 * on the step execution when it starts and the repository filters on it, so this only ever sees
 * work that is already due — normally nothing. Classifying still parses the step JSON, but of the
 * due rows alone rather than of every live step on every tick.
 */
class StepDeadlines {

    record Result(Set<String> timedOutProcessIds, Set<String> dueTimerProcessIds) {
    }

    static Result classify(Collection<StepExecution> due) {
        var timedOut = new LinkedHashSet<String>();
        var dueTimers = new LinkedHashSet<String>();
        for (var stepExecution : due) {
            var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
            if (StepType.TIMER.equals(step.type())) {
                dueTimers.add(stepExecution.getProcessId());
            } else {
                timedOut.add(stepExecution.getProcessId());
            }
        }
        return new Result(timedOut, dueTimers);
    }

    private StepDeadlines() {
    }
}
