package io.mateu.workflow.usersservice.infra.in.ui.pages.usergroups;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.application.out.UserGroupRepository;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
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
    final UserGroupRepository repository;

    @Override
    public ListingData<UserGroupRow> search(String searchText,
                                            NoFilters filters,
                                            Pageable pageable) {
        var data = repository.findAll(searchText, filters, pageable);
        return new ListingData(new Page(searchText, data.page().pageSize(),
                data.page().pageNumber(),
                data.page().totalElements(), data.page().content().stream()
                .map(permission -> new UserGroupRow(
                        permission.getId().id().toString(),
                        permission.getName().name(),
                        permission.getDescription().description()))
                .toList()));
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        repository.deleteAllById(selectedIds.stream()
                .map(UserGroupId::new)
                .toList());
    }

    @Override
    public UserGroupViewModel getView(String id) {
        return viewModel.load(repository
                .findById(new UserGroupId(id))
                .orElseThrow());
    }

    @Override
    public UserGroupViewModel getEditor(String id) {
        return viewModel.load(repository
                .findById(new UserGroupId(id))
                .orElseThrow());
    }

    @Override
    public UserGroupViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
