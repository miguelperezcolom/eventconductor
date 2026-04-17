package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.JwtExtractor;
import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.ListingBackend;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

record TaskRow(String id, String name, String form, String assignedTo, String status, ColumnAction run) {}

@Service
@Slf4j
@Trigger(type = TriggerType.OnLoad, actionId = "search")
@Trigger(type = TriggerType.OnSuccess, actionId = "search", calledActionId = "claim")
@RequiredArgsConstructor
public class Tasks implements ListingBackend<NoFilters, TaskRow> {

    final FormExecutionEntityRepository repository;

    @Override
    public ListingData<TaskRow> search(String searchText, NoFilters noFilters, Pageable pageable, HttpRequest httpRequest) {
        var page = repository.findByStatusAndUser(List.of(
                        FormExecutionStatus.PENDING.name()
                ),
                JwtExtractor.getUsername(httpRequest).orElse(null),
                org.springframework.data.domain.Pageable.ofSize(pageable.size()).withPage(pageable.page()));
        var content = page.getContent().stream()
                .map(entity -> new TaskRow(
                        entity.getId(),
                        entity.getProcessId(),
                        entity.getFormId(),
                        entity.getUserId(),
                        entity.getStatus(),
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

    @Toolbar
    public void claim(List<TaskRow> selectedRows, HttpRequest httpRequest) {
      log.info("claiming " + selectedRows);
      var userId = JwtExtractor.getUsername(httpRequest).orElseThrow();
        selectedRows.forEach(row -> {
            var entity = repository.findById(row.id()).orElseThrow();
            entity.setUserId(userId);
            repository.save(entity);
        });
    }

    public URI run(TaskRow selectedRow) {
        log.info("running " + selectedRow);
        return URI.create("task/" + selectedRow.id());
    }

    @Override
    public boolean selectionEnabled() {
        return true;
    }
}
