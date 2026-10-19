package io.mateu.workflow.application.usecases.escalatetask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies an escalation the engine fired to the task it is about: records its level and when, and for
 * a {@code reassign} gives the task to whoever it names now. Once: a task already at this level (a
 * redelivery) is left alone. A task that is no longer open is only marked — an escalation never
 * reopens a task, and for an {@code escalateTo} the engine's cancellation closes it.
 *
 * <p>Announced as an {@code escalated} task event (and {@code assigned} when it now has an assignee):
 * the hook a notification hangs on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscalateTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final HumanTaskEvents humanTaskEvents;

    public void handle(HumanTaskEscalationRequested escalation) {
        for (var task : formExecutionRepository.findByStepExecutionId(escalation.taskExecutionId())) {
            if (task.escalations() >= escalation.level()) {
                log.debug("Task {} is already at escalation {}; ignoring escalation {}", task.id(),
                        task.escalations(), escalation.level());
                continue;
            }
            var escalated = task.withEscalationLevel(escalation.level()).withEscalatedAt(TaskInboxOrder.nowUtc());
            var to = escalation.reassignTo();
            var reassigned = to != null && task.isOpen();
            if (reassigned) {
                escalated = escalated.withUserId(to.assignee())
                        .withStatus(to.assignee() == null ? FormExecutionStatus.PENDING : FormExecutionStatus.ASSIGNED)
                        .withCandidateUsers(to.candidateUsers())
                        .withCandidateGroups(CreateTaskUseCase.groups(to.candidateGroups()))
                        .withRestricted(to.restricted() || task.isRestricted());
            }
            formExecutionRepository.save(escalated);
            humanTaskEvents.changed(escalated, HumanTaskChanged.ESCALATED);
            if (reassigned && escalated.isAssigned()) {
                humanTaskEvents.changed(escalated, HumanTaskChanged.ASSIGNED);
            }
        }
    }
}
