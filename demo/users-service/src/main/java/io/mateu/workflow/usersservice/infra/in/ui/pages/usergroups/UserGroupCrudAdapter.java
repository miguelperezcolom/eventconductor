package io.mateu.workflow.usersservice.infra.in.ui.pages.usergroups;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.application.query.UserGroupQueryService;
import io.mateu.workflow.usersservice.application.query.dto.UserGroupRow;
import io.mateu.workflow.usersservice.application.usecases.usergroup.delete.DeleteUserGroupCommand;
import io.mateu.workflow.usersservice.application.usecases.usergroup.delete.DeleteUserGroupUseCase;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class UserGroupCrudAdapter implements CrudAdapter<
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
    public ListingData<UserGroupRow> search(String searchText,
                                            NoFilters filters,
                                            Pageable pageable) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteUserGroupUseCase.handle(new DeleteUserGroupCommand(selectedIds));
    }

    @Override
    public UserGroupViewModel getView(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public UserGroupViewModel getEditor(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public UserGroupViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
