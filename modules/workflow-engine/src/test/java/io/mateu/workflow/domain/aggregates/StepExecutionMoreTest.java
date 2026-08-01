package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepExecutionMoreTest {

    private Process process(java.util.List<Variable> variables) {
        return Process.builder().id("p-1").businessKey("bk-1").variables(variables).build();
    }

    private Step actionStep() {
        return new Step("step-1", "wd-1", StepType.ACTION, "Step 1", null, null, null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private Step userTaskStepWithForm(String formId) {
        return new Step("step-1", "wd-1", StepType.USER_TASK, "User Step", null, null, null, null, false, null, formId, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private Step ruleStepWithRule(String ruleId) {
        return new Step("step-1", "wd-1", StepType.RULE, "Rule Step", null, null, null, null, false, null, null, ruleId, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    @Test
    void scheduleRetryIncrementsAttemptCountAndResetsStatus() {
        var step = actionStep();
        var se = StepExecution.create(step, "p-1", 0);
        se.start(process(List.of()));
        se.popEvents();

        se.scheduleRetry();

        assertThat(se.getAttemptCount()).isEqualTo(1);
        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.CREATED);
    }

    @Test
    void scheduleRetryEmitsLogEvent() {
        var step = actionStep();
        var se = StepExecution.create(step, "p-1", 0);

        se.scheduleRetry();

        var events = se.popEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TaskLogEmitted.class);
    }

    @Test
    void startWithUserTaskAndFormIdAddsFormIdToVariables() {
        var step = userTaskStepWithForm("form-42");
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("step-1").stepJson(io.mateu.core.infra.JsonSerializer.toJson(step))
                .status(StepExecutionStatus.CREATED).build();

        se.start(process(List.of(new Variable("v1", "val1"))));

        assertThat(se.getVariables()).anyMatch(v -> "formId".equals(v.name()) && "form-42".equals(v.value()));
        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void startWithUserTaskAndNoFormIdSetsErrorStatus() {
        var step = userTaskStepWithForm(null);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("step-1").stepJson(io.mateu.core.infra.JsonSerializer.toJson(step))
                .status(StepExecutionStatus.CREATED).build();

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        // The log entry plus StepExecutionStatusChanged(ERROR), so the normal failure
        // pipeline (retry/compensation/process status) engages.
        var events = se.popEvents();
        assertThat(events).hasSize(2);
        assertThat(events).anyMatch(e -> e instanceof io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged);
    }

    @Test
    void startWithActionStepEmitsTaskExecutionRequested() {
        var step = actionStep();
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("step-1").stepJson(io.mateu.core.infra.JsonSerializer.toJson(step))
                .status(StepExecutionStatus.CREATED).build();

        se.start(process(List.of()));

        var events = se.popEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TaskExecutionRequested.class);
        var event = (TaskExecutionRequested) events.get(0);
        assertThat(event.taskId()).isEqualTo("");
    }

    @Test
    void startWithUserTaskEmitsTaskExecutionRequestedWithCompleteFormTaskId() {
        var step = userTaskStepWithForm("form-1");
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("step-1").stepJson(io.mateu.core.infra.JsonSerializer.toJson(step))
                .status(StepExecutionStatus.CREATED).build();

        se.start(process(List.of()));

        var events = se.popEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TaskExecutionRequested.class);
        var event = (TaskExecutionRequested) events.get(0);
        assertThat(event.taskId()).isEqualTo("complete-form");
    }

    @Test
    void startWithRuleStepAddsRuleIdVariableAndEvaluateRuleTaskId() {
        var step = ruleStepWithRule("high-value-order");
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("step-1").stepJson(io.mateu.core.infra.JsonSerializer.toJson(step))
                .status(StepExecutionStatus.CREATED).build();

        se.start(process(List.of(new Variable("order.total", "200"))));

        assertThat(se.getVariables()).anyMatch(v -> "ruleId".equals(v.name()) && "high-value-order".equals(v.value()));
        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        var events = se.popEvents();
        assertThat(events).hasSize(1);
        var event = (TaskExecutionRequested) events.get(0);
        assertThat(event.taskId()).isEqualTo("evaluate-rule");
    }

    @Test
    void startWithRuleStepAndNoRuleIdSetsErrorStatus() {
        var step = ruleStepWithRule(null);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1")
                .stepId("step-1").stepJson(io.mateu.core.infra.JsonSerializer.toJson(step))
                .status(StepExecutionStatus.CREATED).build();

        se.start(process(List.of()));

        assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        // The log entry plus StepExecutionStatusChanged(ERROR), so the normal failure
        // pipeline (retry/compensation/process status) engages — same as USER_TASK.
        var events = se.popEvents();
        assertThat(events).hasSize(2);
        assertThat(events).anyMatch(e -> e instanceof io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged);
    }

    @Test
    void scheduleRetryMultipleTimesAccumulatesCount() {
        var step = actionStep();
        var se = StepExecution.create(step, "p-1", 0);

        se.scheduleRetry();
        se.popEvents();
        se.scheduleRetry();
        se.popEvents();
        se.scheduleRetry();

        assertThat(se.getAttemptCount()).isEqualTo(3);
    }
}
