package io.mateu.workflow.usersservice.infra.in.ui.pages.permissions;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.query.PermissionQueryService;
import io.mateu.workflow.usersservice.application.query.dto.PermissionRow;
import io.mateu.workflow.usersservice.application.usecases.permission.delete.DeletePermissionCommand;
import io.mateu.workflow.usersservice.application.usecases.permission.delete.DeletePermissionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Permissions")
public class PermissionsCrudOrchestrator extends Crud<
        PermissionViewModel,
        PermissionViewModel,
        PermissionViewModel,
        NoFilters,
        PermissionRow,
        String
        > {

    final PermissionViewModel viewModel;
    final DeletePermissionUseCase deletePermissionUseCase;
    final PermissionQueryService queryService;

    @Override
    public ListingData<PermissionRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public PermissionViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public PermissionViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public PermissionViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(PermissionViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(PermissionViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deletePermissionUseCase.handle(new DeletePermissionCommand(selectedIds));
    }
}
