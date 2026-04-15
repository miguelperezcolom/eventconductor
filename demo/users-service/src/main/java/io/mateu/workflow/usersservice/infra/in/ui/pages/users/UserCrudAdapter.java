package io.mateu.workflow.usersservice.infra.in.ui.pages.users;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.out.UserRepository;
import io.mateu.workflow.usersservice.application.query.UserQueryService;
import io.mateu.workflow.usersservice.application.query.dto.UserRow;
import io.mateu.workflow.usersservice.application.usecases.user.delete.DeleteUserCommand;
import io.mateu.workflow.usersservice.application.usecases.user.delete.DeleteUserUseCase;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class UserCrudAdapter implements CrudAdapter<
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
    public ListingData<UserRow> search(String searchText,
                                       NoFilters filters,
                                       Pageable pageable, HttpRequest httpRequest) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteUserUseCase.handle(new DeleteUserCommand(selectedIds));
    }

    @Override
    public UserViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public UserViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public UserViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
