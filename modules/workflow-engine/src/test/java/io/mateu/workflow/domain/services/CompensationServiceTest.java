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

    private Step step(String id, boolean compensable, String compensationStepId) {
        return new Step(id, "wd", StepType.ACTION, id, null, null, null, null, false, "topic",
                null, null, null, null, 0, null, null, null, null, 0, 0, compensable, compensationStepId, 0, null);
    }

    private StepExecution exec(String stepId, StepExecutionStatus status, boolean compensable,
                               String compensationStepId, long order, LocalDateTime finishedAt) {
        return StepExecution.builder()
                .id("se-" + stepId).processId("p").workflowDefinitionId("wd").stepId(stepId)
                .stepJson(JsonSerializer.toJson(step(stepId, compensable, compensationStepId)))
                .status(status).order(order).finishedAt(finishedAt)
                .variables(List.of()).build();
    }

    private StepExecution comp(String stepId, StepExecutionStatus status) {
        return exec(stepId, status, false, null, 0, null);
    }

    @Test
    void theOldRollbackableFieldNameStillDeserialisesAsCompensable() {
        // Definitions and in-flight step JSON written before the rename carry `rollbackable`; the
        // @JsonAlias must keep them meaning `compensable`, or an upgrade would silently stop
        // compensating every process already running. Read through the same serializer the engine
        // uses at runtime.
        var step = JsonSerializer.pojoFromJson(
                "{\"id\":\"a\",\"rollbackable\":true,\"compensationStepId\":\"ca\"}", Step.class);
        assertThat(step.compensable()).isTrue();
        assertThat(step.compensationStepId()).isEqualTo("ca");
    }

    @Test
    void noneWhenNoStepFailed() {
        var decision = service.decide(List.of(
                exec("a", StepExecutionStatus.COMPLETED, true, "ca", 1, t0.plusSeconds(1)),
                comp("ca", StepExecutionStatus.CREATED)));

        assertThat(decision.outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    void noneWhenFailedButNothingCompensable() {
        var decision = service.decide(List.of(
                exec("a", StepExecutionStatus.COMPLETED, false, null, 1, t0.plusSeconds(1)),
                exec("b", StepExecutionStatus.ERROR, false, null, 2, t0.plusSeconds(2))));

        assertThat(decision.outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    void compensatesCompletedStepsButNotTheFailedOne() {
        // charge completed (compensation: refund); ship then failed (compensation: unship).
        // Only the completed step is compensated — the failed step committed nothing to undo, so
        // its own compensation must never run.
        var decision = service.decide(List.of(
                exec("charge", StepExecutionStatus.COMPLETED, true, "refund", 1, t0.plusSeconds(1)),
                exec("ship", StepExecutionStatus.ERROR, true, "unship", 2, t0.plusSeconds(2)),
                comp("refund", StepExecutionStatus.CREATED),
                comp("unship", StepExecutionStatus.CREATED)));

        assertThat(decision.outcome()).isEqualTo(Outcome.RUN);
        assertThat(decision.next().getStepId()).isEqualTo("refund");
    }

    @Test
    void aFailedStepAloneCompensatesNothing() {
        // The failure that triggers the rollback, with nothing completed before it: there is no
        // committed work to reverse, so this is a plain error, not a saga rollback.
        var decision = service.decide(List.of(
                exec("charge", StepExecutionStatus.ERROR, true, "refund", 1, t0.plusSeconds(1)),
                comp("refund", StepExecutionStatus.CREATED)));

        assertThat(decision.outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    void compensatesCompletedStepsInReverseExecutionOrder() {
        // a, b completed (compensable); c then failed. Only a and b are compensated — c is not —
        // and they are undone latest-completed first (b before a).
        var a = exec("a", StepExecutionStatus.COMPLETED, true, "ca", 1, t0.plusSeconds(1));
        var b = exec("b", StepExecutionStatus.COMPLETED, true, "cb", 2, t0.plusSeconds(2));
        var c = exec("c", StepExecutionStatus.ERROR, true, "cc", 3, t0.plusSeconds(3));

        // Round 1: latest-COMPLETED (b) is undone first — not c, which failed.
        var first = service.decide(List.of(a, b, c,
                comp("ca", StepExecutionStatus.CREATED),
                comp("cb", StepExecutionStatus.CREATED),
                comp("cc", StepExecutionStatus.CREATED)));
        assertThat(first.outcome()).isEqualTo(Outcome.RUN);
        assertThat(first.next().getStepId()).isEqualTo("cb");

        // Round 2: cb done → a's compensation is next.
        var second = service.decide(List.of(a, b, c,
                comp("ca", StepExecutionStatus.CREATED),
                comp("cb", StepExecutionStatus.COMPLETED),
                comp("cc", StepExecutionStatus.CREATED)));
        assertThat(second.next().getStepId()).isEqualTo("ca");

        // Round 3: cb, ca done → fully rolled back, without ever touching c's compensation (cc).
        var done = service.decide(List.of(a, b, c,
                comp("ca", StepExecutionStatus.COMPLETED),
                comp("cb", StepExecutionStatus.COMPLETED),
                comp("cc", StepExecutionStatus.CREATED)));
        assertThat(done.outcome()).isEqualTo(Outcome.DONE);
    }

    // The compensable work in these is a COMPLETED step "a" (compensation "ca"); "f" is the later
    // failure that triggers the rollback and is itself never compensated.
    @Test
    void waitsWhileACompensationIsInFlight() {
        var decision = service.decide(List.of(
                exec("a", StepExecutionStatus.COMPLETED, true, "ca", 1, t0.plusSeconds(1)),
                exec("f", StepExecutionStatus.ERROR, false, null, 2, t0.plusSeconds(2)),
                comp("ca", StepExecutionStatus.PENDING)));

        assertThat(decision.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(decision.next()).isNull();
    }

    @Test
    void haltsWhenACompensationItselfFailed() {
        var decision = service.decide(List.of(
                exec("a", StepExecutionStatus.COMPLETED, true, "ca", 1, t0.plusSeconds(1)),
                exec("f", StepExecutionStatus.ERROR, false, null, 2, t0.plusSeconds(2)),
                comp("ca", StepExecutionStatus.ERROR)));

        assertThat(decision.outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void waitsWhileACompensationIsAwaitingItsOwnRetry() {
        // A compensation with retries left, parked in AWAITING_RETRY, is still in flight — the
        // chain must wait for it, not treat it as failed or skip past it.
        var decision = service.decide(List.of(
                exec("a", StepExecutionStatus.COMPLETED, true, "ca", 1, t0.plusSeconds(1)),
                exec("f", StepExecutionStatus.ERROR, false, null, 2, t0.plusSeconds(2)),
                comp("ca", StepExecutionStatus.AWAITING_RETRY)));

        assertThat(decision.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(decision.next()).isNull();
    }
}
