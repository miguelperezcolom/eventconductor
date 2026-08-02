package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.domain.services.MessageCorrelation;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import lombok.AccessLevel;
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

@Builder(toBuilder = true)
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
    /**
     * The moment this step needs the engine's attention — a TIMER's due moment or a step's
     * timeout deadline — or null when it needs none (no timeout configured, not started yet, or
     * a TIMER whose date could not be resolved, which fails at start instead).
     *
     * <p>Derived state, materialised so the scheduler can find due work with an indexed range
     * scan instead of evaluating every live step on every tick. It is a pure function of
     * {@code startedAt}, {@code variables} and {@code stepJson}, all frozen at start, and every
     * path that moves the clock recomputes it. {@code withDeadlineAt} is deliberately suppressed
     * so it cannot be set on its own — the builder and the all-args constructor still carry it,
     * because rehydrating a persisted step has to restore the value it was stored with.
     */
    @With(AccessLevel.NONE)
    private LocalDateTime deadlineAt;


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

    /**
     * Moves this step's clock, recomputing {@link #deadlineAt} so the two cannot drift apart.
     * Hand-written on purpose: Lombok's generated {@code withStartedAt} would copy the old
     * deadline, silently freezing a timer at its pre-shift moment. Pause/resume shifts the clock
     * of every in-flight step by the pause duration, and that is the only caller today — but the
     * invariant belongs here, not in the caller.
     */
    public StepExecution withStartedAt(LocalDateTime startedAt) {
        var shifted = toBuilder().startedAt(startedAt).build();
        shifted.deadlineAt = shifted.computeDeadline();
        return shifted;
    }

    /**
     * The deadline implied by the current {@code startedAt}, {@code variables} and step, or null
     * when this step has none or has not started. A TIMER whose date cannot be resolved yields
     * null rather than throwing: {@link #start(Process)} already fails such a step, so there is
     * nothing left for the scheduler to fire.
     */
    private LocalDateTime computeDeadline() {
        if (startedAt == null) {
            return null;
        }
        try {
            return pojoFromJson(stepJson, Step.class).deadlineAt(startedAt, variables);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public StepExecution start(Process process) {
        var variables = process.getVariables() == null ? List.<Variable>of() : process.getVariables();
        this.variables = variables;
        this.startedAt = LocalDateTime.now();
        var step = pojoFromJson(stepJson, Step.class);
        // Materialise the deadline now, while everything it derives from is being frozen. A
        // misconfigured TIMER leaves it null and errors below, so nothing is ever left armed
        // without a moment to fire at.
        this.deadlineAt = computeDeadline();
        if (StepType.START.equals(step.type()) || StepType.FORK.equals(step.type())
                || StepType.JOIN.equals(step.type())) {
            // Pure control-flow nodes involve no worker: START marks the entry point, FORK's
            // fan-out and JOIN's barrier are entirely the orchestrator's eligibility rules
            // (preconditions), so the node itself just completes instantly when started.
            send(new TaskLogEmitted(id, MessageType.Info,
                    step.type() + " step " + step.name() + " passed through."));
            updateStatus(StepExecutionStatus.COMPLETED);
            return this;
        } else if (StepType.USER_TASK.equals(step.type())) {
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
        } else if (StepType.WAIT_FOR_MESSAGE.equals(step.type())) {
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
        } else if (StepType.SEND_MESSAGE.equals(step.type())) {
            // A message throw involves no worker: compute the target correlation key, emit
            // the MessageReceived through the outbox and complete immediately. Delivery is
            // fire-and-forget — it is not acknowledged, and a message that matches no
            // waiting process is discarded on the receiving side (not buffered).
            if (step.messageName() == null || step.messageName().isBlank()
                    || step.correlationExpression() == null || step.correlationExpression().isBlank()) {
                send(new TaskLogEmitted(id, MessageType.Error,
                        "Step " + step.name() + " must define a messageName and a correlationExpression."));
                // updateStatus (not a bare assignment) so the normal failure pipeline
                // engages instead of freezing the process — same as USER_TASK above.
                updateStatus(StepExecutionStatus.ERROR);
                return this;
            }
            var correlationKey = MessageCorrelation.expectedKey(step, process);
            if (correlationKey == null) {
                // Fail loud, unlike guard evaluation: a send whose key cannot be computed
                // cannot fulfil its purpose, and dropping the message silently would leave
                // no trace of the failure.
                send(new TaskLogEmitted(id, MessageType.Error,
                        "Step " + step.name() + ": correlation expression '"
                                + step.correlationExpression() + "' could not be evaluated."));
                updateStatus(StepExecutionStatus.ERROR);
                return this;
            }
            var messageVariables = step.messageVariables() == null ? List.<io.mateu.workflow.dtos.Variable>of()
                    : variables.stream()
                            .filter(variable -> step.messageVariables().contains(variable.name()))
                            .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                            .toList();
            send(new MessageReceived(step.messageName(), correlationKey, messageVariables));
            send(new TaskLogEmitted(id, MessageType.Info,
                    "Message '" + step.messageName() + "' sent with correlation key '" + correlationKey + "'."));
            updateStatus(StepExecutionStatus.COMPLETED);
            return this;
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
        } else if (StepType.PROCESS.equals(step.type())) {
            if (step.childWorkflowDefinitionId() == null || step.childWorkflowDefinitionId().isBlank()) {
                send(new TaskLogEmitted(id, MessageType.Error,
                        "Step " + step.name() + " has no child workflow definition id defined."));
                // updateStatus (not a bare assignment) so the normal failure pipeline
                // engages instead of freezing the process — same as USER_TASK above.
                updateStatus(StepExecutionStatus.ERROR);
                return this;
            }
            // A child workflow involves no worker: the step stays PENDING and
            // NotifyParentStepService completes it when the child process reaches a terminal
            // status. The deterministic businessKey "parent:<stepExecutionId>" makes
            // redeliveries idempotent (CreateProcessUseCase dedupes by businessKey).
            send(new ProcessCreationRequested(step.childWorkflowDefinitionId(), "parent:" + id,
                    variables.stream()
                            .map(variable -> new io.mateu.workflow.dtos.Variable(variable.name(), variable.value()))
                            .toList(),
                    id));
            send(new TaskLogEmitted(id, MessageType.Info,
                    "Started child process of workflow '" + step.childWorkflowDefinitionId()
                            + "' for step " + step.name() + "."));
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
        // The previous attempt's deadline is meaningless now; start() arms a fresh one.
        this.deadlineAt = null;
        send(new TaskLogEmitted(id, MessageType.Info,
                "Auto-retry attempt " + attemptCount + " scheduled for step " + stepId));
    }
}
