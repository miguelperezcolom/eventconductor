package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.JwtExtractor;
import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static io.mateu.uidl.Humanizer.toUpperCaseFirst;

record TaskRow(String id, String name, String form, String assignedTo, Status status, ColumnAction run) {}

@Service
@Slf4j
@Trigger(type = TriggerType.OnLoad, actionId = "search")
@Trigger(type = TriggerType.OnSuccess, actionId = "search", calledActionId = "claim")
@RequiredArgsConstructor
public class Tasks extends Crud<TaskRow, TaskRow, TaskRow, NoFilters, TaskRow, String> {

    final FormExecutionEntityRepository repository;
    final io.mateu.workflow.application.services.TaskAuthorization taskAuthorization;

    // Mateu 271 removed the io.mateu.core.infra.declarative.Listing base class (and the
    // ListingBackend port). A read-only listing page is now a Crud whose CRUD capabilities
    // are disabled; only search + the row/toolbar actions remain.
    @Override
    public ListingData<TaskRow> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        var pageable = searchRequest.pageable();
        var statuses = List.of(FormExecutionStatus.PENDING.name());
        var user = JwtExtractor.getUsername(httpRequest).orElse(null);
        var request = org.springframework.data.domain.PageRequest.of(pageable.page(), pageable.size(),
                org.springframework.data.domain.Sort.by("id"));
        // The same narrowing TasksV2 does. Two listings of the same rows must not disagree about
        // which of them a person is allowed to see.
        var permitted = taskAuthorization.enabled() ? taskAuthorization.permittedFormIds() : null;
        var page = permitted == null
                ? repository.findTaskSummariesByStatusAndUser(statuses, user, request)
                : permitted.isEmpty()
                        ? org.springframework.data.domain.Page.<FormExecutionEntityRepository.TaskSummary>empty(request)
                        : repository.findTaskSummariesByStatusAndUserAndForms(statuses, user, permitted, request);
        var content = page.getContent().stream()
                .map(task -> new TaskRow(
                        task.getId(),
                        task.getProcessId(),
                        task.getFormId(),
                        task.getUserId(),
                        mapStatus(task.getStatus()),
                        ColumnAction.builder()
                                .methodNameInCrud("run")
                                .label("Run")
                                .build()
                ))
                .toList();
        return ListingData.<TaskRow>builder()
                .page(Page.<TaskRow>builder()
                        .pageSize(page.getSize())
                        .pageNumber(page.getNumber())
                        .totalElements(page.getTotalElements())
                        .content(content)
                        .build())
                .build();
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
            case "RUNNING" -> StatusType.WARNING;
            case "COMPLETED" -> StatusType.SUCCESS;
            case "ERROR" -> StatusType.DANGER;
            default -> StatusType.NONE;
        };
        return new Status(statusType, toUpperCaseFirst(status));
    }

    @Toolbar
    @Transactional
    public void claim(List<TaskRow> selectedRows, HttpRequest httpRequest) {
        if (selectedRows.isEmpty()) {
            return;
        }
        var userId = JwtExtractor.getUsername(httpRequest).orElseThrow();
        var ids = selectedRows.stream().map(TaskRow::id).toList();
        var claimed = repository.claim(ids, userId);
        log.info("claimed {} of {} selected tasks for user {}", claimed, ids.size(), userId);
    }

    public UICommand run(TaskRow selectedRow, HttpRequest httpRequest) {
        log.info("running " + selectedRow);
        return UICommand.builder()
                .type(UICommandType.DispatchEvent)
                .data(new DispatchEventData(
                        "navigation-requested",
                        NavigationRequestedPayload.builder()
                                .route("/forms/task/" + selectedRow.id())
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
