package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
@Getter
public final class StepExecution extends AggregateRoot implements Identifiable {

    private String id;
    @HiddenInList
    private String processId;
    @HiddenInList
    private String workflowDefinitionId;
    private String stepId;
    @HiddenInList
    private String stepJson;
    @HiddenInList
    private List<Variable> variables;
    private StepExecutionStatus status;
    private String workerId;
    private long order;
    private LocalDateTime startedAt;
    /** Number of execution attempts already made (0 = first attempt, 1 = first retry, …). */
    private int attemptCount;


    public static StepExecution create(Step step, String processId, int position) {
        var stepExecution = StepExecution.builder()
                .id(UUID.randomUUID().toString())
                .processId(processId)
                .workflowDefinitionId(step.workflowDefinitionId())
                .stepId(step.id())
                .stepJson(toJson(step))
                .variables(List.of())
                .status(StepExecutionStatus.CREATED)
                .order(position)
                .build();
        return stepExecution;
    }

    @Override
    public String id() {
        return id;
    }

    public StepExecution start(List<Variable> variables) {
        this.variables = variables;
        this.startedAt = LocalDateTime.now();
        var step = pojoFromJson(stepJson, Step.class);
        if (StepType.USER_TASK.equals(step.type())) {
            if (step.formId() == null || step.formId().isEmpty()) {
                status = StepExecutionStatus.ERROR;
                send(new TaskLogEmitted(id, MessageType.Error, "Step " + step.name() + " has no form id defined."));
                return this;
            }
            var taskVariables = new ArrayList<>(variables);
            taskVariables.add(new Variable("formId", step.formId()));
            this.variables = taskVariables;
            send(new TaskExecutionRequested(id, processId, workflowDefinitionId, stepId, "complete-form", taskVariables.stream()
                    .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                    .toList()));
        } else if (StepType.RULE.equals(step.type())) {
            if (step.ruleId() == null || step.ruleId().isEmpty()) {
                status = StepExecutionStatus.ERROR;
                send(new TaskLogEmitted(id, MessageType.Error, "Step " + step.name() + " has no rule id defined."));
                return this;
            }
            var taskVariables = new ArrayList<>(variables);
            taskVariables.add(new Variable("ruleId", step.ruleId()));
            this.variables = taskVariables;
            send(new TaskExecutionRequested(id, processId, workflowDefinitionId, stepId, "evaluate-rule", taskVariables.stream()
                    .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                    .toList()));
        } else {
            send(new TaskExecutionRequested(id, processId, workflowDefinitionId, stepId, "", variables.stream()
                    .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                    .toList()));
        }
        status = StepExecutionStatus.PENDING;
        return this;
    }

    public void updateStatus(StepExecutionStatus status) {
        this.status = status;
        send(new StepExecutionStatusChanged(id, TaskStatus.valueOf(status.name()), List.of()));
    }

    /**
     * Resets this step execution for a new attempt.
     * Increments {@code attemptCount}, sets status back to CREATED and logs the retry.
     * Does NOT emit a domain event — the caller is responsible for driving the next cycle.
     */
    public void scheduleRetry() {
        this.attemptCount++;
        this.status = StepExecutionStatus.CREATED;
        send(new TaskLogEmitted(id, MessageType.Info,
                "Auto-retry attempt " + attemptCount + " scheduled for step " + stepId));
    }
}
