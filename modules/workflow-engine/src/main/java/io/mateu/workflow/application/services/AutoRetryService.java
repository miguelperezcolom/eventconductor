package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Decides whether a step that just failed gets another attempt, and if so parks it in
 * {@link StepExecutionStatus#AWAITING_RETRY} for a backoff delay.
 *
 * <p>Called where the failure is <b>applied</b> — the locked worker-report update and the timeout
 * scan — so the ERROR/TIMEOUT and the parking commit together. Deciding it later, in the handler of
 * the status-change event, left a window in which a step with retries left was persisted as ERROR:
 * anything that read the process in between (a sibling branch completing and stepping the process
 * over, the status recompute, a synchronous caller waiting for the reply) took that ERROR as final,
 * errored the process and started its rollback, while the retry was still coming. The event handler
 * keeps the same decision as a fallback for failures applied elsewhere.
 *
 * <p>The caller saves the step.
 */
@Service
@RequiredArgsConstructor
public class AutoRetryService {

    final BackoffPolicy backoffPolicy;
    final WorkflowMetrics workflowMetrics;

    /**
     * Parks the step for a retry if it is in ERROR/TIMEOUT, has attempts left and the failure was
     * not declared final.
     *
     * <p>Deliberately not re-dispatched here: an immediate step-over would burn the whole retry
     * budget in milliseconds against a worker that fails fast (bad config, downstream 500) — a hot
     * loop that hammers the failing dependency and defeats retries, whose point is to wait out a
     * transient fault. The timeout scheduler wakes the step once the delay has elapsed.
     *
     * @param nonRetryable the worker declared the failure final (a 4xx, a business error marked
     *                     non-retryable): waiting it out cannot change the answer
     * @return whether the step was parked
     */
    public boolean parkForRetryIfAttemptsLeft(StepExecution stepExecution, boolean nonRetryable) {
        if (nonRetryable || stepExecution.getStepJson() == null
                || !(StepExecutionStatus.ERROR.equals(stepExecution.getStatus())
                        || StepExecutionStatus.TIMEOUT.equals(stepExecution.getStatus()))) {
            return false;
        }
        if (stepExecution.isContainer()
                || io.mateu.workflow.domain.aggregates.StepExecution.LOOP_EXHAUSTED.equals(stepExecution.getLoopDecision())) {
            // A forEach container fails because an iteration spent its own retries, and a loop fails
            // because it hit a cap: retrying either would only multiply the one or hit the other again.
            return false;
        }
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        if (stepExecution.getAttemptCount() >= step.retries()) {
            return false;
        }
        stepExecution.scheduleRetry(backoffPolicy.nextDelay(stepExecution.getAttemptCount() + 1));
        workflowMetrics.retryPerformed(stepExecution.getWorkflowDefinitionId(),
                WorkflowMetrics.RetryTrigger.AUTO);
        return true;
    }
}
