package io.mateu.workflow.application.usecases.releasetask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.application.services.TaskActionRefusedException;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskTimeline;
import io.mateu.workflow.application.services.TaskVisibility;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Hands a task back: by whoever holds it, or a task admin. It goes back to whoever it is offered to —
 * its candidates, or everybody when it names nobody — PENDING again.
 */
@Service
@RequiredArgsConstructor
public class ReleaseTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final TaskInbox taskInbox;
    final HumanTaskEvents humanTaskEvents;
    final TaskTimeline taskTimeline;

    public FormExecution handle(String taskId, TaskCaller caller) {
        var task = taskInbox.get(taskId, caller);
        if (!TaskVisibility.canRelease(task, caller)) {
            throw new TaskActionRefusedException("Task " + taskId + " cannot be released by " + caller.subject(), false);
        }
        var released = task.withUserId(null).withStatus(FormExecutionStatus.PENDING);
        formExecutionRepository.save(released);
        taskTimeline.log(released.stepExecutionId(), "form " + released.formId() + " released by " + caller.subject());
        humanTaskEvents.changed(released, HumanTaskChanged.RELEASED);
        return released;
    }
}
