package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.services.CompensationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessOperationRulesTest {

    final ProcessOperationRules rules = new ProcessOperationRules(new CompensationService());

    @Test
    void downstreamFollowsLinksJoinsChoicesAndTimeoutRoutes() {
        var steps = List.of(
                se("start", "START", Map.of(), StepExecutionStatus.COMPLETED),
                se("fork", "FORK", Map.of("preconditionStepId", "start"), StepExecutionStatus.COMPLETED),
                se("a", "ACTION", Map.of("preconditionStepId", "fork", "onTimeoutStepId", "late"), StepExecutionStatus.COMPLETED),
                se("b", "ACTION", Map.of("preconditionStepId", "fork"), StepExecutionStatus.COMPLETED),
                se("late", "ACTION", Map.of(), StepExecutionStatus.CREATED),
                se("join", "JOIN", Map.of("preconditionStepIds", List.of("a", "b")), StepExecutionStatus.COMPLETED),
                se("choice", "CHOICE", Map.of("preconditionStepId", "join"), StepExecutionStatus.COMPLETED),
                se("x", "ACTION", Map.of("preconditionStepId", "choice"), StepExecutionStatus.ERROR));

        assertThat(ProcessOperationRules.downstreamOf("a", steps)).containsExactlyInAnyOrder("a", "late", "join", "choice", "x");
        assertThat(ProcessOperationRules.downstreamOf("b", steps)).containsExactlyInAnyOrder("b", "join", "choice", "x");
        assertThat(ProcessOperationRules.downstreamOf("x", steps)).containsExactly("x");
    }

    @Test
    void retryFromIsRefusedWhenItCannotBeCarriedOut() {
        var steps = List.of(
                se("start", "START", Map.of(), StepExecutionStatus.COMPLETED),
                se("a", "ACTION", Map.of("preconditionStepId", "start"), StepExecutionStatus.ERROR),
                se("b", "ACTION", Map.of("preconditionStepId", "start"), StepExecutionStatus.ERROR),
                se("c", "ACTION", Map.of("preconditionStepId", "a"), StepExecutionStatus.CREATED));
        var failed = process(ProcessStatus.ERROR);

        assertThatThrownBy(() -> rules.planRetryFrom(process(ProcessStatus.COMPLETED), "a", steps))
                .hasMessageContaining("RUNNING, PAUSED or ERROR");
        assertThatThrownBy(() -> rules.planRetryFrom(failed, "zz", steps)).hasMessageContaining("no step");
        assertThatThrownBy(() -> rules.planRetryFrom(failed, "c", steps)).hasMessageContaining("has not run yet");
        assertThatThrownBy(() -> rules.planRetryFrom(failed, "a", steps)).hasMessageContaining("[b]");
        var plan = rules.planRetryFrom(failed, "start", steps);
        assertThat(plan.stepIds()).containsExactlyInAnyOrder("start", "a", "b", "c");
    }

    @Test
    void completeStepRules() {
        var running = process(ProcessStatus.RUNNING);
        var lock = se("lock", "LOCK", Map.of(), StepExecutionStatus.PENDING);
        var created = se("c", "ACTION", Map.of(), StepExecutionStatus.CREATED);
        var pending = se("p", "ACTION", Map.of(), StepExecutionStatus.AWAITING_RETRY);
        var done = se("d", "ACTION", Map.of(), StepExecutionStatus.COMPLETED);
        var compensation = se("undo", "ACTION", Map.of(), StepExecutionStatus.PENDING);
        var compensable = se("charge", "ACTION", Map.of("compensable", true, "compensationStepId", "undo"),
                StepExecutionStatus.COMPLETED);
        var all = List.of(lock, created, pending, done, compensation, compensable);

        assertThat(rules.whyNotCompleteStep(running, pending, all)).isNull();
        assertThat(rules.whyNotCompleteStep(running, created, all)).contains("CREATED");
        assertThat(rules.whyNotCompleteStep(running, done, all)).contains("already COMPLETED");
        assertThat(rules.whyNotCompleteStep(running, lock, all)).contains("LOCK");
        assertThat(rules.whyNotCompleteStep(running, compensation, all)).contains("compensation");
        assertThat(rules.whyNotSetVariables(process(ProcessStatus.PENDING))).isNotNull();
        assertThat(rules.whyNotSetVariables(process(ProcessStatus.PAUSED))).isNull();
    }

    @Test
    void aFailedSagaWithACompensationToRunIsRollingBack() {
        var charge = se("charge", "ACTION", Map.of("compensable", true, "compensationStepId", "refund"),
                StepExecutionStatus.COMPLETED);
        var ship = se("ship", "ACTION", Map.of("preconditionStepId", "charge"), StepExecutionStatus.ERROR);
        var refund = se("refund", "ACTION", Map.of(), StepExecutionStatus.CREATED);
        var steps = List.of(charge, ship, refund);

        assertThat(rules.rollbackUnderway(process(ProcessStatus.ERROR), steps)).isTrue();
        assertThat(rules.whyNotCompleteStep(process(ProcessStatus.ERROR), ship, steps)).contains("rollback");
        assertThat(rules.rollbackUnderway(process(ProcessStatus.RUNNING), steps)).isFalse();
    }

    @Test
    void iterationsAndPassesFollowTheCurrentView() {
        var running = process(ProcessStatus.RUNNING);
        var container = se("line", "ACTION", Map.of("forEach", Map.of("items", "lines")), StepExecutionStatus.PENDING);
        var iteration = se("line", "ACTION", Map.of(), StepExecutionStatus.PENDING).toBuilder()
                .id("it-0").parentExecutionId(container.getId()).itemIndex(0).build();
        var oldPass = se("poll", "ACTION", Map.of(), StepExecutionStatus.COMPLETED).toBuilder()
                .id("poll-0").superseded(true).build();
        var all = List.of(container, iteration, oldPass);

        assertThat(rules.whyNotCompleteStep(running, iteration, all)).isNull();
        assertThat(rules.whyNotCompleteStep(running, oldPass, all)).contains("history");
        container.updateStatus(StepExecutionStatus.ERROR);
        assertThat(rules.whyNotCompleteStep(running, iteration, all)).contains("forEach step");
        // Planned over the current view: the iteration is not a step of its own.
        assertThat(rules.planRetryFrom(process(ProcessStatus.ERROR), "line", all).executions())
                .containsExactly(container);
    }

    static Process process(ProcessStatus status) {
        return Process.builder().id("p").workflowDefinitionId("w").status(status).variables(List.of()).build();
    }

    static StepExecution se(String id, String type, Map<String, Object> props, StepExecutionStatus status) {
        var json = new LinkedHashMap<String, Object>(props);
        json.put("id", id);
        json.put("type", type);
        return StepExecution.builder().id("se-" + id).processId("p").stepId(id).stepJson(toJson(json)).status(status)
                .startedAt(status == StepExecutionStatus.CREATED ? null : LocalDateTime.now())
                .finishedAt(LocalDateTime.now()).variables(List.of()).build();
    }
}
