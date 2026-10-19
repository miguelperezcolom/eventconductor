package io.mateu.workflow.application.usecases.createtask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Opens the task a USER_TASK asks for. Who it is for and how urgent it is arrive already rendered, in
 * the {@code __task} variable (see {@link HumanTaskAssignment}); a task from an engine that sends none
 * names nobody, as every task did before assignment existed. The engine's own variables are not kept
 * among the ones the task shows.
 */
@Service
@RequiredArgsConstructor
public class CreateTaskUseCase {

    final FormExecutionRepository formExecutionRepository;
    final FormsMetrics formsMetrics;
    final HumanTaskEvents humanTaskEvents;

    public void handle(CreateTaskCommand command) {
        var variables = command.variables() == null ? List.<Variable>of() : command.variables();
        var assignment = assignmentOf(variables);
        var task = FormExecution.builder()
                .id(UUID.randomUUID().toString())
                .formId(command.formId())
                .processId(command.processId())
                .stepId(command.stepId())
                .stepExecutionId(command.stepExecutionId())
                .status(assignment.assignee() == null ? FormExecutionStatus.PENDING : FormExecutionStatus.ASSIGNED)
                .userId(assignment.assignee())
                .variables(variables.stream()
                        .filter(variable -> !HumanTaskAssignment.isInternalVariable(variable.name()))
                        .toList())
                .values(List.of())
                .candidateUsers(assignment.candidateUsers())
                .candidateGroups(groups(assignment.candidateGroups()))
                .priority(assignment.priorityOrDefault())
                .dueDate(utc(assignment.dueDate()))
                .followUpDate(utc(assignment.followUpDate()))
                .businessKey(assignment.businessKey())
                .createdAt(TaskInboxOrder.nowUtc())
                .escalationLevel(0)
                .restricted(assignment.restricted())
                .build();
        formExecutionRepository.save(task);
        formsMetrics.taskCreated(command.formId());
        humanTaskEvents.changed(task, HumanTaskChanged.CREATED);
        if (task.isAssigned()) {
            humanTaskEvents.changed(task, HumanTaskChanged.ASSIGNED);
        }
    }

    /** The task's assignment, from its {@code __task} variable; nobody when it has none or it is unreadable. */
    static HumanTaskAssignment assignmentOf(List<Variable> variables) {
        return variables.stream()
                .filter(variable -> HumanTaskAssignment.VARIABLE.equals(variable.name()))
                .findFirst()
                .map(variable -> HumanTaskAssignment.fromJson(variable.value()))
                .orElse(HumanTaskAssignment.none());
    }

    /** Group names as they are compared (see {@link TaskCaller#groupName}). */
    public static List<String> groups(List<String> groups) {
        return groups == null ? List.of() : groups.stream().map(TaskCaller::groupName)
                .filter(group -> !group.isEmpty()).distinct().toList();
    }

    /** An ISO instant as the UTC date-time tasks are stored in; null for none or an unreadable one. */
    public static LocalDateTime utc(String instant) {
        if (instant == null || instant.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
