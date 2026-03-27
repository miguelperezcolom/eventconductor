package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes;

import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.core.infra.declarative.AutoListOrchestrator;
import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.ListingBackend;
import io.mateu.workflow.controlplaneservice.application.query.ChangeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Title("Changes")
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Changes implements ListingBackend<NoFilters, ChangeRow> {

    final ChangeQueryService queryService;

    @Override
    public ListingData<ChangeRow> search(String searchText, NoFilters filters, Pageable pageable, HttpRequest httpRequest) {
        var found = queryService.findAll(searchText, filters, pageable);
        return ListingData.<ChangeRow>builder()
                .page(Page.<ChangeRow>builder()
                        .searchSignature(found.page().searchSignature())
                        .totalElements(found.page().totalElements())
                        .pageSize(found.page().pageSize())
                        .pageNumber(found.page().pageNumber())
                        .content(found.page().content().stream()
                                .map(dto -> new ChangeRow(
                                        dto.pageId(), dto.page(), dto.country(), dto.language(),
                                        new Status(StatusType.DANGER, dto.status().name())))
                                .toList())
                        .build())
                .build();
    }

    @Toolbar
    public void createRelease(List<ChangeRow> selectedRows, HttpRequest httpRequest) {}
}
