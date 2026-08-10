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
import java.util.Objects;
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
    /**
     * The message this step is waiting for, or null when it is not a live WAIT_FOR_MESSAGE.
     * Materialised at start, together with {@link #awaitingCorrelationKey}, so an arriving
     * message finds its subscribers with an indexed lookup instead of a walk over every step
     * waiting anywhere in the engine.
     */
    @With(AccessLevel.NONE)
    private String awaitingMessageName;
    /**
     * The correlation key an arriving message must carry to wake this step, or null when it has
     * none — including a correlation expression that cannot be evaluated, which is how the
     * fail-closed contract survives being indexed: a null key equals nothing, so it matches
     * nothing.
     *
     * <p>Kept current rather than frozen: {@link #rearmedFor(Process)} recomputes it whenever the
     * process variables it derives from change, so a message correlates against the process as it
     * is now — the same contract as evaluating the expression on arrival, which is what this
     * replaced.
     */
    @With(AccessLevel.NONE)
    private String awaitingCorrelationKey;
    /**
     * Optimistic-locking version, or null for a step that has never been persisted.
     *
     * <p>The fence for the window Kafka's ownership guarantee does not cover. A consumer group
     * gives a partition to exactly one consumer, but during a rebalance the outgoing pod can
     * still be mid-flight on a record the incoming one has just been handed. This makes that
     * harmless: the stale writer's update matches no row at its version and fails, instead of
     * quietly overwriting the new owner's work.
     *
     * <p>Costs nothing when there is no conflict — no waiting, no lock to hold, no connection
     * parked — which is why it can replace a pessimistic lock rather than sit next to one.
     */
    @With(AccessLevel.NONE)
    private Integer version;

    /**
     * The id of the DYNAMIC step execution that injected this one at runtime, or null for a step
     * materialised the ordinary way (from the definition, at process creation).
     *
     * <p>The exact idempotency key for injection: a re-delivered {@code StepsInjected} finds the
     * children it already created marked with the injecting step's id and injects nothing more.
     * Also the record of provenance the graph and tooling read to show which steps a running
     * process grew that its definition never declared.
     */
    private String injectedByStepExecutionId;


    public static StepExecution create(Step step, String processId, int position) {
        return create(step, processId, position, null);
    }

    /**
     * Materialises a step execution, optionally marked as injected by a DYNAMIC step. Normal
     * creation passes null; runtime injection passes the injecting step execution's id, which is
     * what makes a re-delivered injection idempotent and what the graph reads as provenance.
     */
    public static StepExecution create(Step step, String processId, int position,
                                       String injectedByStepExecutionId) {
        var stepExecution = StepExecution.builder()
                .id(UUID.randomUUID().toString())
                .processId(processId)
                .workflowDefinitionId(step.workflowDefinitionId())
                .stepId(step.id())
                .stepJson(toJson(step))
                .variables(List.of())
                .status(StepExecutionStatus.CREATED)
                .order(position)
                .injectedByStepExecutionId(injectedByStepExecutionId)
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
     * Recomputes every derived lookup field — the deadline and the message subscription — from
     * the state this step already carries, returning {@code this} untouched when nothing moved.
     *
     * <p>Two callers, for two reasons. Process variables change while a step waits (a parallel
     * branch completing can change the very variable a correlation expression reads), and before
     * the key was stored that was free: it was evaluated on arrival, against whatever the process
     * held then. Storing it means every path that updates process variables has to come through
     * here. And a step that started under a version without these fields carries none, so the
     * boot-time rearm brings it up to date the same way.
     */
    public StepExecution rearmedFor(Process process) {
        if (startedAt == null) {
            return this;
        }
        var step = pojoFromJson(stepJson, Step.class);
        var deadline = computeDeadline();
        var waiting = StepExecutionStatus.PENDING.equals(status)
                && StepType.WAIT_FOR_MESSAGE.equals(step.type())
                && step.messageName() != null && !step.messageName().isBlank();
        var messageName = waiting ? step.messageName() : null;
        var correlationKey = waiting ? MessageCorrelation.expectedKey(step, process) : null;
        if (Objects.equals(deadline, deadlineAt)
                && Objects.equals(messageName, awaitingMessageName)
                && Objects.equals(correlationKey, awaitingCorrelationKey)) {
            return this;
        }
        var rearmed = toBuilder().build();
        rearmed.deadlineAt = deadline;
        rearmed.awaitingMessageName = messageName;
        rearmed.awaitingCorrelationKey = correlationKey;
        return rearmed;
    }

    /**
     * This step's type as a plain name, for persistence to store in a column of its own.
     *
     * <p>Null when the step JSON cannot be read, which is the same answer a row written before
     * that column existed gives — and both are handled the same way where it is queried: as
     * "unknown", not as "not the type you are looking for".
     */
    public String stepTypeName() {
        try {
            var type = pojoFromJson(stepJson, Step.class).type();
            return type == null ? null : type.name();
        } catch (RuntimeException e) {
            return null;
        }
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
            // Materialise what this step is waiting for, so an arriving message can find it by
            // index. A correlation expression that will not evaluate yields a null key, which
            // matches nothing — the same fail-closed outcome as evaluating it on arrival.
            this.awaitingMessageName = step.messageName();
            this.awaitingCorrelationKey = MessageCorrelation.expectedKey(step, process);
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
        send(new StepExecutionStatusChanged(id, TaskStatus.valueOf(status.name()), List.of(), processId));
    }

    /**
     * Returns this step to the state it was created in, so the process can run it again as if it
     * never had: no attempt behind it, no start, no deadline and nothing subscribed.
     *
     * <p>Distinct from {@link #scheduleRetry(java.time.Duration)}, which is the automatic retry of a step that just
     * failed and deliberately keeps counting attempts against the step's own retry budget. This is
     * an operator restarting a whole process from the beginning, where a step that already
     * succeeded is going to run a second time and its history of the first run is no longer the
     * history of this attempt. The frozen {@code variables} are left alone because
     * {@link #start(Process)} overwrites them with the process's, which is the only thing that
     * could be right by then.
     *
     * <p>Does NOT emit a status change — the caller drives the next cycle — but does log, because
     * a step going from COMPLETED back to CREATED is the kind of thing an operator reading the
     * timeline later needs to see explained.
     */
    public void resetForRerun() {
        this.status = StepExecutionStatus.CREATED;
        this.startedAt = null;
        this.finishedAt = null;
        this.attemptCount = 0;
        this.deadlineAt = null;
        this.awaitingMessageName = null;
        this.awaitingCorrelationKey = null;
        this.workerId = null;
        send(new TaskLogEmitted(id, MessageType.Info,
                "Step " + stepId + " reset to run again from the start of the process"));
    }

    /**
     * Schedules a new attempt after a backoff delay instead of re-dispatching immediately.
     * Increments {@code attemptCount}, parks the step in {@link StepExecutionStatus#AWAITING_RETRY}
     * with {@code deadlineAt} set to {@code now + backoff}, and logs it. The timeout scheduler wakes
     * the step once the deadline passes and {@link #releaseForRetry()} returns it to CREATED so the
     * process re-dispatches it. A zero or negative backoff still parks the step for the scheduler's
     * next tick rather than spinning here — a failing worker must never be hammered in a tight loop.
     *
     * <p>Does NOT emit a {@code StepExecutionStatusChanged} — the step is not yet running again and
     * the wait is driven off {@code deadlineAt}, not off the normal status pipeline.
     */
    public void scheduleRetry(java.time.Duration backoff) {
        this.attemptCount++;
        this.status = StepExecutionStatus.AWAITING_RETRY;
        this.finishedAt = null;
        this.workerId = null;
        var delay = backoff == null || backoff.isNegative() ? java.time.Duration.ZERO : backoff;
        this.deadlineAt = LocalDateTime.now().plus(delay);
        send(new TaskLogEmitted(id, MessageType.Info,
                "Auto-retry attempt " + attemptCount + " for step " + stepId
                        + " scheduled in " + delay.toMillis() + " ms (at " + deadlineAt + ")"));
    }

    /**
     * Returns a step parked in {@link StepExecutionStatus#AWAITING_RETRY} to CREATED so the process
     * can dispatch it again. Keeps {@code attemptCount} (this is a retry, not a fresh run) and clears
     * the backoff deadline; {@link #start(Process)} arms a new one. Does NOT emit a domain event —
     * the caller drives the next step-over.
     */
    public void releaseForRetry() {
        this.status = StepExecutionStatus.CREATED;
        this.finishedAt = null;
        this.deadlineAt = null;
        send(new TaskLogEmitted(id, MessageType.Info,
                "Backoff elapsed; re-dispatching step " + stepId + " (attempt " + attemptCount + ")"));
    }
}
