package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

import java.util.List;

/**
 * Gives a step that declares no timeout a fallback one, so that a dispatch or a reply going
 * missing is something the engine recovers from rather than something a person notices weeks
 * later.
 *
 * <p>Without a deadline a step is not merely un-timed-out, it is invisible: the scheduler's scan
 * is an index range over the deadline, so a step that has none is never looked at again. If the
 * request never reaches the worker, or the worker's answer never reaches the broker, the process
 * stops there permanently and nothing reports it. That is exactly what happened during a broker
 * outage on a four-hour run — 3 356 processes ended in that state.
 *
 * <p>Applied when a definition is read, not when a step runs, so a process carries the effective
 * timeout in the copy it froze at creation and cannot have it changed underneath it.
 *
 * <h2>Which steps</h2>
 *
 * <p>Only ACTION and RULE. Those are machine work with a worker on the other end, and a worker
 * that has not answered in a long time has almost certainly not received the request. Everything
 * else is left alone on purpose:
 *
 * <ul>
 *   <li>USER_TASK waits for a person, and people take days.</li>
 *   <li>PROCESS waits for a child whose duration is the child's business.</li>
 *   <li>WAIT_FOR_MESSAGE is designed to wait indefinitely; that is what it is for.</li>
 *   <li>TIMER already has a due moment, and control-flow steps never dispatch at all.</li>
 * </ul>
 *
 * <p>Off unless configured, because the right value is a property of the deployment and a wrong
 * one turns healthy slow work into spurious retries. Set it to comfortably more than the slowest
 * task any worker legitimately performs.
 */
public final class StepTimeoutDefaults {

    public static WorkflowDefinition applyTo(WorkflowDefinition definition, long defaultTimeoutMillis) {
        if (defaultTimeoutMillis <= 0 || definition == null || definition.steps() == null) {
            return definition;
        }
        var steps = definition.steps().stream()
                .map(step -> needsDefault(step) ? step.withTimeout(defaultTimeoutMillis) : step)
                .toList();
        return withSteps(definition, steps);
    }

    private static boolean needsDefault(Step step) {
        return step.timeout() <= 0
                && (StepType.ACTION.equals(step.type()) || StepType.RULE.equals(step.type()));
    }

    private static WorkflowDefinition withSteps(WorkflowDefinition definition, List<Step> steps) {
        return new WorkflowDefinition(
                definition.id(), definition.name(), definition.version(), definition.description(),
                definition.limitConcurrentExecutions(), definition.maxConcurrentExecutions(),
                definition.enqueueOnLimit(), definition.cronExpression(),
                definition.defaultMaxStepExecutions(), steps,
                definition.paused(), definition.disabled(), definition.archived(),
                definition.declaredDisabled(), definition.declaredArchived());
    }

    private StepTimeoutDefaults() {
    }
}
