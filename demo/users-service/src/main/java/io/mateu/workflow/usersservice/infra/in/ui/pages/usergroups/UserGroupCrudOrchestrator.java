package io.mateu.workflow.usersservice.infra.in.ui.pages.usergroups;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.query.UserGroupQueryService;
import io.mateu.workflow.usersservice.application.query.dto.UserGroupRow;
import io.mateu.workflow.usersservice.application.usecases.usergroup.delete.DeleteUserGroupCommand;
import io.mateu.workflow.usersservice.application.usecases.usergroup.delete.DeleteUserGroupUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("User Groups")
public class UserGroupCrudOrchestrator extends Crud<
        UserGroupViewModel,
        UserGroupViewModel,
        UserGroupViewModel,
        NoFilters,
        UserGroupRow,
        String
        > {

    final UserGroupViewModel viewModel;
    final DeleteUserGroupUseCase deleteUserGroupUseCase;
    final UserGroupQueryService queryService;

    @Override
    public ListingData<UserGroupRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public UserGroupViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public UserGroupViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public UserGroupViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(UserGroupViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(UserGroupViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteUserGroupUseCase.handle(new DeleteUserGroupCommand(selectedIds));
    }
}
