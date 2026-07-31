package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Decides, from the current step executions of a process, which compensation to run next.
 *
 * <p>Implements process-level saga rollback: once a step has finally failed (its retries
 * exhausted), EVERY step that has executed and declares a compensation is compensated,
 * sequentially, in <b>reverse execution order</b> (the latest-executed step is undone first).
 * The failed step itself is compensated too — it attempted its work, and this keeps the
 * single-step saga a special case of the cascade.
 *
 * <p>Pure and side-effect free: it derives the next action entirely from persisted state, so
 * the caller can invoke it on every terminal event and it stays idempotent under redelivery
 * and across restarts. The caller applies the decision (starts the returned compensation, or
 * marks the process COMPENSATED once the chain is done).
 */
@Service
public class CompensationService {

    public enum Outcome {
        /** The process has not failed, or it has nothing to compensate: do nothing. */
        NONE,
        /** Start {@link Decision#next()} — the next compensation in reverse execution order. */
        RUN,
        /** A compensation is already in flight: wait for its completion event. */
        WAITING,
        /** Every required compensation has completed: the process is fully rolled back. */
        DONE,
        /** A compensation itself failed (after its own retries): halt the chain. */
        FAILED
    }

    public record Decision(Outcome outcome, StepExecution next) {
        static Decision of(Outcome outcome) {
            return new Decision(outcome, null);
        }
    }

    /**
     * @param executions all step executions of a single process
     * @return what to do next to advance (or finish) the compensation of that process
     */
    public Decision decide(List<StepExecution> executions) {
        boolean failed = executions.stream().anyMatch(e -> isFailure(e.getStatus()));
        if (!failed) {
            return Decision.of(Outcome.NONE);
        }

        Map<String, StepExecution> byStepId = new HashMap<>();
        for (var execution : executions) {
            byStepId.put(execution.getStepId(), execution);
        }

        // Steps that ran (completed, or finally failed) and declare a compensation, ordered
        // latest-executed first so we undo them in reverse.
        var toCompensate = executions.stream()
                .filter(e -> hasRun(e.getStatus()))
                .map(e -> new Compensable(e, step(e)))
                .filter(c -> c.step().rollbackable()
                        && c.step().compensationStepId() != null
                        && !c.step().compensationStepId().isBlank())
                .sorted(REVERSE_EXECUTION_ORDER)
                .toList();

        if (toCompensate.isEmpty()) {
            // Failed, but no rollbackable step ran: a plain error, not a rollback.
            return Decision.of(Outcome.NONE);
        }

        // Walk in reverse execution order; the first compensation that is not yet COMPLETED
        // decides the outcome. Returning at that point enforces strict sequencing — only one
        // compensation is ever in flight, and the next starts only once the previous completes.
        for (var compensable : toCompensate) {
            var compensation = byStepId.get(compensable.step().compensationStepId());
            if (compensation == null) {
                // Dangling link (validated against at load time): skip defensively so one bad
                // reference cannot wedge the whole rollback.
                continue;
            }
            switch (compensation.getStatus()) {
                case COMPLETED -> { /* already undone — look further back in the chain */ }
                case CREATED -> { return new Decision(Outcome.RUN, compensation); }
                case PENDING, RUNNING -> { return Decision.of(Outcome.WAITING); }
                case ERROR, TIMEOUT, CANCELLED -> { return Decision.of(Outcome.FAILED); }
            }
        }
        return Decision.of(Outcome.DONE);
    }

    private record Compensable(StepExecution execution, Step step) {}

    // finishedAt DESC (latest-executed first), tie-broken by definition order DESC. finishedAt
    // is always set for hasRun() steps (terminal statuses stamp it); nullsLast is defensive.
    private static final Comparator<Compensable> REVERSE_EXECUTION_ORDER = Comparator
            .comparing((Compensable c) -> c.execution().getFinishedAt(),
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingLong(c -> c.execution().getOrder())
            .reversed();

    private static boolean isFailure(StepExecutionStatus status) {
        return status == StepExecutionStatus.ERROR || status == StepExecutionStatus.TIMEOUT;
    }

    private static boolean hasRun(StepExecutionStatus status) {
        return status == StepExecutionStatus.COMPLETED
                || status == StepExecutionStatus.ERROR
                || status == StepExecutionStatus.TIMEOUT;
    }

    private static Step step(StepExecution execution) {
        return pojoFromJson(execution.getStepJson(), Step.class);
    }
}
