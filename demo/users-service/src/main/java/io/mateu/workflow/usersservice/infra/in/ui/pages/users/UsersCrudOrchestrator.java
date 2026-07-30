package io.mateu.workflow.usersservice.infra.in.ui.pages.users;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.query.UserQueryService;
import io.mateu.workflow.usersservice.application.query.dto.UserRow;
import io.mateu.workflow.usersservice.application.usecases.user.delete.DeleteUserCommand;
import io.mateu.workflow.usersservice.application.usecases.user.delete.DeleteUserUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Users")
public class UsersCrudOrchestrator extends Crud<
        UserViewModel,
        UserViewModel,
        UserViewModel,
        NoFilters,
        UserRow,
        String
        > {

    final UserViewModel viewModel;
    final DeleteUserUseCase deleteUserUseCase;
    final UserQueryService queryService;

    @Override
    public ListingData<UserRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public UserViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public UserViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public UserViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(UserViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(UserViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteUserUseCase.handle(new DeleteUserCommand(selectedIds));
    }
}
