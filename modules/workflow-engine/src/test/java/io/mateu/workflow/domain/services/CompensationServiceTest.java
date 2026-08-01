package io.mateu.workflow.domain.services;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.services.CompensationService.Outcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationServiceTest {

    private final CompensationService service = new CompensationService();
    private final LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    private Step step(String id, boolean rollbackable, String compensationStepId) {
        return new Step(id, "wd", StepType.ACTION, id, null, null, null, null, false, "topic",
                null, null, null, null, 0, null, null, null, null, 0, 0, rollbackable, compensationStepId, 0, null);
    }

    private StepExecution exec(String stepId, StepExecutionStatus status, boolean rollbackable,
                               String compensationStepId, long order, LocalDateTime finishedAt) {
        return StepExecution.builder()
                .id("se-" + stepId).processId("p").workflowDefinitionId("wd").stepId(stepId)
                .stepJson(JsonSerializer.toJson(step(stepId, rollbackable, compensationStepId)))
                .status(status).order(order).finishedAt(finishedAt)
                .variables(List.of()).build();
    }

    private StepExecution comp(String stepId, StepExecutionStatus status) {
        return exec(stepId, status, false, null, 0, null);
    }

    @Test
    void noneWhenNoStepFailed() {
        var decision = service.decide(List.of(
                exec("a", StepExecutionStatus.COMPLETED, true, "ca", 1, t0.plusSeconds(1)),
                comp("ca", StepExecutionStatus.CREATED)));

        assertThat(decision.outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    void noneWhenFailedButNothingRollbackable() {
        var decision = service.decide(List.of(
                exec("a", StepExecutionStatus.COMPLETED, false, null, 1, t0.plusSeconds(1)),
                exec("b", StepExecutionStatus.ERROR, false, null, 2, t0.plusSeconds(2))));

        assertThat(decision.outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    void runsCompensationOfTheFailedRollbackableStep() {
        var decision = service.decide(List.of(
                exec("charge", StepExecutionStatus.ERROR, true, "refund", 1, t0.plusSeconds(1)),
                comp("refund", StepExecutionStatus.CREATED)));

        assertThat(decision.outcome()).isEqualTo(Outcome.RUN);
        assertThat(decision.next().getStepId()).isEqualTo("refund");
    }

    @Test
    void compensatesInReverseExecutionOrder() {
        // a, b completed; c failed — all rollbackable, none compensated yet.
        var a = exec("a", StepExecutionStatus.COMPLETED, true, "ca", 1, t0.plusSeconds(1));
        var b = exec("b", StepExecutionStatus.COMPLETED, true, "cb", 2, t0.plusSeconds(2));
        var c = exec("c", StepExecutionStatus.ERROR, true, "cc", 3, t0.plusSeconds(3));

        // Round 1: latest-executed (c) is undone first.
        var first = service.decide(List.of(a, b, c,
                comp("ca", StepExecutionStatus.CREATED),
                comp("cb", StepExecutionStatus.CREATED),
                comp("cc", StepExecutionStatus.CREATED)));
        assertThat(first.outcome()).isEqualTo(Outcome.RUN);
        assertThat(first.next().getStepId()).isEqualTo("cc");

        // Round 2: cc done → b's compensation is next.
        var second = service.decide(List.of(a, b, c,
                comp("ca", StepExecutionStatus.CREATED),
                comp("cb", StepExecutionStatus.CREATED),
                comp("cc", StepExecutionStatus.COMPLETED)));
        assertThat(second.next().getStepId()).isEqualTo("cb");

        // Round 3: cc, cb done → a's compensation is last.
        var third = service.decide(List.of(a, b, c,
                comp("ca", StepExecutionStatus.CREATED),
                comp("cb", StepExecutionStatus.COMPLETED),
                comp("cc", StepExecutionStatus.COMPLETED)));
        assertThat(third.next().getStepId()).isEqualTo("ca");

        // Round 4: all done → the process is fully rolled back.
        var done = service.decide(List.of(a, b, c,
                comp("ca", StepExecutionStatus.COMPLETED),
                comp("cb", StepExecutionStatus.COMPLETED),
                comp("cc", StepExecutionStatus.COMPLETED)));
        assertThat(done.outcome()).isEqualTo(Outcome.DONE);
    }

    @Test
    void waitsWhileACompensationIsInFlight() {
        var decision = service.decide(List.of(
                exec("c", StepExecutionStatus.ERROR, true, "cc", 1, t0.plusSeconds(1)),
                comp("cc", StepExecutionStatus.PENDING)));

        assertThat(decision.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(decision.next()).isNull();
    }

    @Test
    void haltsWhenACompensationItselfFailed() {
        var decision = service.decide(List.of(
                exec("c", StepExecutionStatus.ERROR, true, "cc", 1, t0.plusSeconds(1)),
                comp("cc", StepExecutionStatus.ERROR)));

        assertThat(decision.outcome()).isEqualTo(Outcome.FAILED);
    }
}
