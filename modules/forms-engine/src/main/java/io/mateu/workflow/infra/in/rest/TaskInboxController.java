package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.FormSubmission;
import io.mateu.workflow.application.services.TaskActionRefusedException;
import io.mateu.workflow.application.services.TaskCallers;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.application.services.TaskNotFoundException;
import io.mateu.workflow.application.usecases.claimtask.ClaimTaskUseCase;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskCommand;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskUseCase;
import io.mateu.workflow.application.usecases.reassigntask.ReassignTaskUseCase;
import io.mateu.workflow.application.usecases.releasetask.ReleaseTaskUseCase;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskQuery;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A person's human tasks over HTTP — the inbox the forms UI shows, for a front-end of your own.
 *
 * <pre>
 *   GET  /forms/api/tasks?scope=all|mine|candidate|groups&amp;overdue=&amp;minPriority=&amp;dueBefore=
 *                        &amp;processId=&amp;businessKey=&amp;formId=&amp;status=&amp;sort=priority|due|created&amp;page=&amp;size=
 *   GET  /forms/api/tasks/{id}
 *   POST /forms/api/tasks/{id}/claim
 *   POST /forms/api/tasks/{id}/release
 *   POST /forms/api/tasks/{id}/complete      {"values": {"decision": "APPROVE"}}
 *   POST /forms/api/tasks/{id}/assign        {"assignee": "bob", "candidateUsers": [], "candidateGroups": []}  (task admins)
 * </pre>
 *
 * <p>Always as the caller ({@link TaskCallers}): what a task names and what its form requires decide
 * what they see and may do, exactly as in the UI. An anonymous request is refused (401) — the API is
 * for people. A task the caller may not see answers 404, like one that does not exist.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/forms/api/tasks")
@RequiredArgsConstructor
public class TaskInboxController {

    final TaskInbox taskInbox;
    final TaskCallers taskCallers;
    final ClaimTaskUseCase claimTaskUseCase;
    final ReleaseTaskUseCase releaseTaskUseCase;
    final ReassignTaskUseCase reassignTaskUseCase;
    final CompleteTaskUseCase completeTaskUseCase;

    /** One task as the API shows it; {@code variables} and {@code values} only on {@code GET /{id}}. */
    public record TaskView(String id, String formId, String processId, String businessKey, String stepId,
                           String status, String assignee, List<String> candidateUsers, List<String> candidateGroups,
                           int priority, String dueDate, String followUpDate, String createdAt, boolean overdue,
                           int escalationLevel, String escalatedAt, Map<String, String> variables,
                           Map<String, String> values) {
    }

    public record TaskList(List<TaskView> items, long total, int page, int size) {
    }

    public record CompleteRequest(Map<String, Object> values) {
    }

    public record AssignRequest(String assignee, List<String> candidateUsers, List<String> candidateGroups) {
    }

    @GetMapping
    public TaskList search(@RequestParam(required = false) String scope,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer minPriority,
                           @RequestParam(required = false) Boolean overdue,
                           @RequestParam(required = false) String dueBefore,
                           @RequestParam(required = false) String processId,
                           @RequestParam(required = false) String businessKey,
                           @RequestParam(required = false) String formId,
                           @RequestParam(required = false) String sort,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "50") int size) {
        var query = new TaskQuery(TaskQuery.Scope.parse(scope), statuses(status), minPriority, overdue,
                moment(dueBefore), processId, businessKey, formId, TaskQuery.Sort.parse(sort), page, size);
        var found = taskInbox.search(query, caller());
        var now = TaskInboxOrder.nowUtc();
        return new TaskList(found.items().stream().map(task -> view(task, now, false)).toList(),
                found.total(), found.page(), found.size());
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable String id) {
        return view(taskInbox.get(id, caller()), TaskInboxOrder.nowUtc(), true);
    }

    @PostMapping("/{id}/claim")
    public TaskView claim(@PathVariable String id) {
        return view(claimTaskUseCase.handle(id, caller()), TaskInboxOrder.nowUtc(), false);
    }

    @PostMapping("/{id}/release")
    public TaskView release(@PathVariable String id) {
        return view(releaseTaskUseCase.handle(id, caller()), TaskInboxOrder.nowUtc(), false);
    }

    @PostMapping("/{id}/complete")
    public TaskView complete(@PathVariable String id, @RequestBody(required = false) CompleteRequest body) {
        var caller = caller();
        var values = body == null || body.values() == null ? List.<Value>of() : body.values().entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> new Value(entry.getKey(), entry.getValue().toString()))
                .toList();
        completeTaskUseCase.handle(new CompleteTaskCommand(id, values), caller);
        return view(taskInbox.get(id, caller), TaskInboxOrder.nowUtc(), false);
    }

    @PostMapping("/{id}/assign")
    public TaskView assign(@PathVariable String id, @RequestBody AssignRequest body) {
        return view(reassignTaskUseCase.handle(id, caller(), body.assignee(), body.candidateUsers(),
                body.candidateGroups()), TaskInboxOrder.nowUtc(), false);
    }

    @ExceptionHandler(TaskNotFoundException.class)
    ProblemDetail onNotFound(TaskNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TaskActionRefusedException.class)
    ProblemDetail onRefused(TaskActionRefusedException e) {
        return ProblemDetail.forStatusAndDetail(e.forbidden() ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(FlowAuthorizationDeniedException.class)
    ProblemDetail onDenied(FlowAuthorizationDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, FormSubmission.IncompleteSubmissionException.class,
            FormSubmission.OversizedValueException.class})
    ProblemDetail onInvalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail onStatus(ResponseStatusException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason());
    }

    private TaskCaller caller() {
        var caller = taskCallers.current();
        if (!caller.known()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The task API acts for a signed-in user.");
        }
        return caller;
    }

    static List<FormExecutionStatus> statuses(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(status -> !status.isEmpty())
                .map(status -> {
                    try {
                        return FormExecutionStatus.valueOf(status.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("status must be PENDING, ASSIGNED, COMPLETED or CANCELLED, not '"
                                + status + "'");
                    }
                })
                .toList();
    }

    /** An ISO instant, offset date-time or (UTC) local date-time; null for none. */
    static LocalDateTime moment(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // not an instant
        }
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(text).toInstant(), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // not with an offset
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dueBefore must be an ISO-8601 instant or date-time, not '" + text + "'");
        }
    }

    static TaskView view(FormExecution task, LocalDateTime now, boolean detail) {
        Map<String, String> variables = null;
        Map<String, String> values = null;
        if (detail) {
            variables = new LinkedHashMap<>();
            for (var variable : task.variables() == null ? List.<io.mateu.workflow.domain.Variable>of() : task.variables()) {
                variables.put(variable.name(), variable.value());
            }
            values = new LinkedHashMap<>();
            for (var value : task.values() == null ? List.<Value>of() : task.values()) {
                values.put(value.name(), value.value());
            }
        }
        return new TaskView(task.id(), task.formId(), task.processId(), task.businessKey(), task.stepId(),
                task.status().name(), task.userId(), task.candidateUsers(), task.candidateGroups(),
                task.priorityOrDefault(), instant(task.dueDate()), instant(task.followUpDate()),
                instant(task.createdAt()), task.isOverdue(now), task.escalations(), instant(task.escalatedAt()),
                variables, values);
    }

    private static String instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC).toString();
    }
}
