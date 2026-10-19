package io.mateu.workflow.application.usecases.reassigntask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.application.services.TaskActionRefusedException;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskTimeline;
import io.mateu.workflow.application.services.TaskVisibility;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * A task admin decides who an open task is for: an assignee (it becomes ASSIGNED to them), candidates,
 * or both — replacing what it named. Naming nobody at all makes it open to everybody who may work on its
 * form.
 */
@Service
@RequiredArgsConstructor
public class ReassignTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final TaskInbox taskInbox;
    final HumanTaskEvents humanTaskEvents;
    final TaskTimeline taskTimeline;

    public FormExecution handle(String taskId, TaskCaller caller, String assignee, List<String> candidateUsers,
                                List<String> candidateGroups) {
        var task = taskInbox.get(taskId, caller);
        if (!TaskVisibility.canReassign(task, caller)) {
            throw new TaskActionRefusedException("Only a task admin reassigns a task, and only an open one", !caller.admin());
        }
        var holder = assignee == null || assignee.isBlank() ? null : assignee.trim();
        var users = candidateUsers == null ? List.<String>of() : candidateUsers.stream()
                .filter(user -> user != null && !user.isBlank()).map(String::trim).distinct().toList();
        var groups = CreateTaskUseCase.groups(candidateGroups);
        var reassigned = task.withUserId(holder)
                .withStatus(holder == null ? FormExecutionStatus.PENDING : FormExecutionStatus.ASSIGNED)
                .withCandidateUsers(users)
                .withCandidateGroups(groups)
                .withRestricted(holder != null || !users.isEmpty() || !groups.isEmpty());
        formExecutionRepository.save(reassigned);
        taskTimeline.log(reassigned.stepExecutionId(), "form " + reassigned.formId() + " reassigned by "
                + caller.subject() + (holder == null ? "" : " to " + holder)
                + (users.isEmpty() ? "" : ", candidate users " + users)
                + (groups.isEmpty() ? "" : ", candidate groups " + groups));
        humanTaskEvents.changed(reassigned, HumanTaskChanged.ASSIGNED);
        return reassigned;
    }
}
