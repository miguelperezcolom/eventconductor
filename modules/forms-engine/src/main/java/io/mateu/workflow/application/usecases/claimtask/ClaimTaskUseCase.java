package io.mateu.workflow.application.usecases.claimtask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.application.services.TaskActionRefusedException;
import io.mateu.workflow.application.services.TaskAuthorization;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskTimeline;
import io.mateu.workflow.application.services.TaskVisibility;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Takes a task: an open one nobody holds, by somebody it is offered to (see {@link TaskVisibility})
 * whose roles and scopes its form allows. Atomic — of two people claiming one task at once, one gets
 * it. The task becomes ASSIGNED to the caller.
 */
@Service
@RequiredArgsConstructor
public class ClaimTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final FormRepository formRepository;
    final TaskInbox taskInbox;
    final TaskAuthorization taskAuthorization;
    final HumanTaskEvents humanTaskEvents;
    final TaskTimeline taskTimeline;

    /**
     * @throws io.mateu.workflow.application.services.TaskNotFoundException when the caller may not see it
     * @throws TaskActionRefusedException when it is not theirs to claim (closed, or somebody holds it)
     * @throws io.mateu.workflow.security.FlowAuthorizationDeniedException when its form requires what they lack
     */
    public FormExecution handle(String taskId, TaskCaller caller) {
        var task = taskInbox.get(taskId, caller);
        taskAuthorization.refuseIfCallerMayNot("claim", formRepository.findById(task.formId()).orElse(null), taskId);
        if (TaskVisibility.holds(task, caller) && task.isOpen()) {
            return task;
        }
        if (!TaskVisibility.canClaim(task, caller) || !formExecutionRepository.claim(taskId, caller.subject())) {
            throw new TaskActionRefusedException("Task " + taskId + " cannot be claimed by " + caller.subject()
                    + (task.isAssigned() ? ": " + task.userId() + " holds it" : ": it is " + task.status()), false);
        }
        var claimed = formExecutionRepository.findById(taskId).orElseThrow();
        taskTimeline.log(claimed.stepExecutionId(), "form " + claimed.formId() + " claimed by " + caller.subject());
        humanTaskEvents.changed(claimed, HumanTaskChanged.CLAIMED);
        return claimed;
    }
}
