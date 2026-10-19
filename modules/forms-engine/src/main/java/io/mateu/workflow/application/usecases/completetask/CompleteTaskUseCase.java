package io.mateu.workflow.application.usecases.completetask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.services.FormSubmission;
import io.mateu.workflow.application.services.TaskAuthorization;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.worker.WorkerReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompleteTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final FormRepository formRepository;
    final StreamBridge streamBridge;
    final FormsMetrics formsMetrics;
    final TaskAuthorization taskAuthorization;
    final io.mateu.workflow.application.services.HumanTaskEvents humanTaskEvents;
    final io.mateu.workflow.application.services.TaskInbox taskInbox;

    /**
     * Closes a human task and tells the engine — the reply first, and through {@link WorkerReply},
     * so a broker that refuses it is retried and then thrown at the submitter instead of dropped.
     *
     * <p>This is the reply that can least afford to be lost. A form arrives over HTTP, not from a
     * topic, so there is no uncommitted offset to make Kafka redeliver it; and a USER_TASK step is
     * deliberately given no fallback deadline, because people take days. Nothing anywhere would
     * notice: the step waits forever for an answer that was already given.
     *
     * <p>Hence the order. Replying before saving means both possible failures leave the task open
     * and re-submittable — a refused reply saves nothing, and a save that fails after a delivered
     * reply leaves a task the user can submit again. The second reply is harmless: the engine
     * ignores a status update for a step that already reached a terminal state.
     */
    public void handle(CompleteTaskCommand command) {
        complete(command, formExecutionRepository.findById(command.taskId()).orElseThrow());
    }

    /**
     * Completes the task on behalf of {@code caller}, who must hold it — the rule every page applies,
     * here for the REST API and anything else that acts for a person rather than for the system.
     *
     * @throws io.mateu.workflow.application.services.TaskNotFoundException when they may not see it
     * @throws io.mateu.workflow.application.services.TaskActionRefusedException when they do not hold it
     */
    public void handle(CompleteTaskCommand command, io.mateu.workflow.domain.TaskCaller caller) {
        var task = taskInbox.get(command.taskId(), caller);
        if (!io.mateu.workflow.application.services.TaskVisibility.canComplete(task, caller)) {
            throw new io.mateu.workflow.application.services.TaskActionRefusedException("Task " + command.taskId()
                    + " is completed by whoever holds it" + (task.isAssigned() ? " (" + task.userId() + ")" : "; claim it first"),
                    false);
        }
        complete(command, task);
    }

    private void complete(CompleteTaskCommand command, FormExecution open) {

        // A task is completed once. Re-submitting one that is already closed used to overwrite the
        // values it was completed with and send the engine a second reply — harmless to the engine,
        // which ignores an update for a step that already finished, but it silently rewrote the
        // record of what the person actually submitted. Same guard, and same reason, as the
        // terminal-status guard on a step execution.
        if (!FormExecutionStatus.PENDING.equals(open.status())
                && !FormExecutionStatus.ASSIGNED.equals(open.status())) {
            log.warn("Task {} is already {} — ignoring a second completion", command.taskId(), open.status());
            return;
        }

        var form = formRepository.findById(open.formId()).orElse(null);

        // May this person do this work at all. Here rather than only in the page, because completion
        // also arrives from the MCP tool and from anything else that holds this use case — and a
        // check that lives in one of three callers is a check two callers do not make.
        taskAuthorization.refuseIfCallerMayNot("complete", form, command.taskId());

        // What arrived, reduced to what this form declares, and refused outright if a required
        // field is missing. The values are posted by a browser and become process variables that
        // later steps read: without this, which variables a process carries is whoever-submitted's
        // to decide. See FormSubmission.
        var values = FormSubmission.accepted(form, command.values(), command.taskId());

        var execution = open
                .withValues(values)
                .withStatus(FormExecutionStatus.COMPLETED);

        emitLog(execution);

        WorkerReply.send(streamBridge, new TaskStatusChanged(
                execution.stepExecutionId(),
                TaskStatus.COMPLETED,
                execution.values().stream().map(v -> new Variable(v.name(), v.value())).toList(),
                execution.processId()));

        formExecutionRepository.save(execution);

        formsMetrics.taskCompleted(execution.formId(), FormsMetrics.durationOf(execution));

        humanTaskEvents.changed(execution, io.mateu.workflow.dtos.events.integration.HumanTaskChanged.COMPLETED);
    }

    /**
     * The log line is a nicety — it annotates the process timeline. Losing it costs a line of
     * history, so it must never be the reason the status reply below does not go out.
     */
    private void emitLog(FormExecution execution) {
        try {
            streamBridge.send("upstream", new TaskLogEmitted(
                    execution.stepExecutionId(),
                    MessageType.Info,
                    "form " + execution.formId() + " completed by " + execution.userId()));
        } catch (RuntimeException e) {
            log.warn("Could not publish the completion log line for task {}; continuing to the status reply",
                    execution.id(), e);
        }
    }

}
