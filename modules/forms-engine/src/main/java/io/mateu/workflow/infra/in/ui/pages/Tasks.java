package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.JwtExtractor;
import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.services.TaskCallers;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.application.usecases.claimtask.ClaimTaskUseCase;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.TaskQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;

record TaskRow(String id, String name, String form, int priority, String due, Status overdue,
               String assignedTo, String offeredTo, Status status, ColumnAction run) {}

@Service
@Slf4j
@Trigger(type = TriggerType.OnLoad, actionId = "search")
@Trigger(type = TriggerType.OnSuccess, actionId = "search", calledActionId = "claim")
@RequiredArgsConstructor
public class Tasks extends Crud<TaskRow, TaskRow, TaskRow, TaskFilters, TaskRow, String> {

    final TaskInbox taskInbox;
    final TaskCallers taskCallers;
    final ClaimTaskUseCase claimTaskUseCase;

    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public Class<TaskFilters> filtersClass() {
        return TaskFilters.class;
    }

    @Override
    public Class<TaskRow> rowClass() {
        return TaskRow.class;
    }

    // Mateu 271 removed the io.mateu.core.infra.declarative.Listing base class (and the
    // ListingBackend port). A read-only listing page is now a Crud whose CRUD capabilities
    // are disabled; only search + the row/toolbar actions remain.
    //
    // What a person may see is the inbox's to decide (TaskInbox: what the task names, and what its
    // form requires) — the same rule as Tasks v2, the REST API and the MCP tools, so no two listings
    // of the same tasks disagree. Most urgent first, then soonest due.
    @Override
    public ListingData<TaskRow> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        var pageable = searchRequest.pageable();
        var filters = searchRequest.filters() instanceof TaskFilters f ? f : null;
        var caller = taskCallers.forUser(JwtExtractor.getUsername(httpRequest).orElse(null));
        var query = new TaskQuery(
                filters == null || filters.scope() == null ? TaskQuery.Scope.ALL : filters.scope().scope(),
                null,
                filters == null ? null : filters.minPriority(),
                filters != null && filters.overdueOnly() ? Boolean.TRUE : null,
                null,
                filters == null ? null : filters.processId(),
                filters == null ? null : filters.businessKey(),
                null,
                TaskQuery.Sort.PRIORITY,
                pageable == null ? 0 : pageable.page(),
                pageable == null ? TaskQuery.DEFAULT_SIZE : pageable.size());
        var page = taskInbox.search(query, caller);
        var now = TaskInboxOrder.nowUtc();
        var content = page.items().stream()
                .map(task -> new TaskRow(
                        task.id(),
                        task.businessKey() != null ? task.businessKey() : task.processId(),
                        task.formId(),
                        task.priorityOrDefault(),
                        task.dueDate() == null ? "" : DUE.format(task.dueDate()) + " UTC",
                        task.isOverdue(now) ? new Status(StatusType.DANGER, "Overdue")
                                : task.escalations() > 0 ? new Status(StatusType.WARNING, "Escalated")
                                : new Status(StatusType.NONE, ""),
                        task.userId(),
                        offeredTo(task),
                        mapStatus(task.status().name()),
                        ColumnAction.builder()
                                .methodNameInCrud("run")
                                .label("Run")
                                .build()
                ))
                .toList();
        return ListingData.<TaskRow>builder()
                .page(Page.<TaskRow>builder()
                        .pageSize(page.size())
                        .pageNumber(page.page())
                        .totalElements(page.total())
                        .content(content)
                        .build())
                .build();
    }

    /** Who an unassigned task is offered to, in a few words. */
    static String offeredTo(FormExecution task) {
        var parts = new java.util.ArrayList<String>();
        if (!task.candidateUsers().isEmpty()) {
            parts.add(String.join(", ", task.candidateUsers()));
        }
        if (!task.candidateGroups().isEmpty()) {
            parts.add("groups " + String.join(", ", task.candidateGroups()));
        }
        return parts.isEmpty() ? (task.isRestricted() ? "" : "anyone") : String.join("; ", parts);
    }

    @Override
    public boolean canView() {
        return false;
    }

    @Override
    public boolean canEdit() {
        return false;
    }

    @Override
    public boolean canCreate() {
        return false;
    }

    @Override
    public boolean canDelete() {
        return false;
    }

    @Override
    public TaskRow view(String id, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TaskRow edit(String id, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String save(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TaskRow creationForm(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllById(List<String> ids, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    private Status mapStatus(String status) {
        StatusType statusType = switch (status) {
            case "PENDING" -> StatusType.INFO;
            case "ASSIGNED" -> StatusType.WARNING;
            case "COMPLETED" -> StatusType.SUCCESS;
            case "CANCELLED", "ERROR" -> StatusType.DANGER;
            default -> StatusType.NONE;
        };
        return new Status(statusType, toUpperCaseFirst(status));
    }

    @Toolbar
    public void claim(List<TaskRow> selectedRows, HttpRequest httpRequest) {
        if (selectedRows.isEmpty()) {
            return;
        }
        var caller = taskCallers.forUser(JwtExtractor.getUsername(httpRequest).orElseThrow());
        var claimed = 0;
        for (var row : selectedRows) {
            try {
                claimTaskUseCase.handle(row.id(), caller);
                claimed++;
            } catch (RuntimeException e) {
                // Somebody else's already, or not offered to this person: the rest still get claimed.
                log.info("task {} not claimed by {}: {}", row.id(), caller.subject(), e.getMessage());
            }
        }
        log.info("claimed {} of {} selected tasks for user {}", claimed, selectedRows.size(), caller.subject());
    }

    public UICommand run(TaskRow selectedRow, HttpRequest httpRequest) {
        log.info("running " + selectedRow);
        return UICommand.builder()
                .type(UICommandType.DispatchEvent)
                .data(new DispatchEventData(
                        "navigation-requested",
                        NavigationRequestedPayload.builder()
                                .route(io.mateu.workflow.infra.in.ui.FormsRoutes.task(selectedRow.id()))
                                .consumedRoute("")
                                .baseUrl(httpRequest.getBaseUrl())
                                .uriPrefix("")
                                .serverSideType("io.mateu.workflow.infra.in.ui.FormsHome")
                                .build()
                ))
                .build();
    }

    @Override
    public boolean selectionEnabled() {
        return true;
    }
}
