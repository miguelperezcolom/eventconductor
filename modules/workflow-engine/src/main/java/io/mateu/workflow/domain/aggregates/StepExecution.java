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
    /** Set when the step reaches a terminal status (COMPLETED, CANCELLED, ERROR, TIMEOUT). */
    private LocalDateTime finishedAt;
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
                send(new TaskLogEmitted(id, MessageType.Error, "Step " + step.name() + " has no form id defined."));
                // updateStatus (not a bare assignment) so StepExecutionStatusChanged is
                // emitted and the normal failure pipeline (retry/compensation/process
                // status) engages instead of freezing the process.
                updateStatus(StepExecutionStatus.ERROR);
                return this;
            }
            var taskVariables = new ArrayList<>(variables);
            taskVariables.add(new Variable("formId", step.formId()));
            this.variables = taskVariables;
            send(new TaskExecutionRequested(id, processId, workflowDefinitionId, stepId, "complete-form", taskVariables.stream()
                    .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                    .toList()));
        } else if (StepType.MESSAGE.equals(step.type())) {
            // A message catch involves no worker: the step just stays PENDING and
            // CorrelateMessageUseCase completes it when a matching MessageReceived
            // arrives. The wait is durable — only persisted state is involved.
            if (step.messageName() == null || step.messageName().isBlank()) {
                send(new TaskLogEmitted(id, MessageType.Error,
                        "Step " + step.name() + " has no message name defined."));
                // updateStatus (not a bare assignment) so the normal failure pipeline
                // engages instead of freezing the process — same as USER_TASK above.
                updateStatus(StepExecutionStatus.ERROR);
                return this;
            }
            send(new TaskLogEmitted(id, MessageType.Info,
                    "Waiting for message '" + step.messageName() + "' on step " + step.name() + "."));
        } else if (StepType.TIMER.equals(step.type())) {
            // A timer involves no worker: the step just stays PENDING and the timer
            // scheduler completes it once the due moment passes. The due moment is
            // recomputed from persisted state, so the wait survives restarts.
            try {
                var dueAt = step.timerDueAt(startedAt, variables);
                send(new TaskLogEmitted(id, MessageType.Info,
                        "Timer armed for step " + step.name() + ", due at " + dueAt + "."));
            } catch (IllegalArgumentException e) {
                send(new TaskLogEmitted(id, MessageType.Error,
                        "Step " + step.name() + ": " + e.getMessage()));
                // updateStatus (not a bare assignment) so the normal failure pipeline
                // engages instead of freezing the process — same as USER_TASK above.
                updateStatus(StepExecutionStatus.ERROR);
                return this;
            }
        } else if (StepType.RULE.equals(step.type())) {
            if (step.ruleId() == null || step.ruleId().isEmpty()) {
                send(new TaskLogEmitted(id, MessageType.Error, "Step " + step.name() + " has no rule id defined."));
                // updateStatus (not a bare assignment) so the normal failure pipeline
                // engages instead of freezing the process — same as USER_TASK above.
                updateStatus(StepExecutionStatus.ERROR);
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
        if (status.isTerminal()) {
            this.finishedAt = LocalDateTime.now();
        } else {
            this.finishedAt = null;
        }
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
        this.finishedAt = null;
        send(new TaskLogEmitted(id, MessageType.Info,
                "Auto-retry attempt " + attemptCount + " scheduled for step " + stepId));
    }
}
