package io.mateu.workflow.usersservice.infra.in.ui.pages.roles;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.query.RoleQueryService;
import io.mateu.workflow.usersservice.application.query.dto.RoleRow;
import io.mateu.workflow.usersservice.application.usecases.role.delete.DeleteRoleCommand;
import io.mateu.workflow.usersservice.application.usecases.role.delete.DeleteRoleUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Roles")
public class RolesCrudOrchestrator extends Crud<
        RoleViewModel,
        RoleViewModel,
        RoleViewModel,
        NoFilters,
        RoleRow,
        String
        > {

    final RoleViewModel viewModel;
    final DeleteRoleUseCase deleteRoleUseCase;
    final RoleQueryService queryService;

    @Override
    public ListingData<RoleRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public RoleViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public RoleViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public RoleViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(RoleViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(RoleViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteRoleUseCase.handle(new DeleteRoleCommand(selectedIds));
    }
}
