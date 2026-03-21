package io.mateu.workflow.usersservice.infra.in.ui.pages.roles;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class RoleCrudAdapter implements CrudAdapter<
        RoleViewModel,
        RoleViewModel,
        RoleViewModel,
        NoFilters,
        RoleRow,
        String
        > {

    final RoleViewModel viewModel;
    final RoleRepository repository;

    @Override
    public ListingData<RoleRow> search(String searchText,
                                       NoFilters filters,
                                       Pageable pageable) {
        var data = repository.findAll(searchText, filters, pageable);
        return new ListingData(new Page(searchText, data.page().pageSize(),
                data.page().pageNumber(),
                data.page().totalElements(), data.page().content().stream()
                .map(permission -> new RoleRow(
                        permission.getId().id().toString(),
                        permission.getName().name(),
                        permission.getDescription().description()))
                .toList()));
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        repository.deleteAllById(selectedIds.stream()
                .map(RoleId::new)
                .toList());
    }

    @Override
    public RoleViewModel getView(String id) {
        return viewModel.load(repository
                .findById(new RoleId(id))
                .orElseThrow());
    }

    @Override
    public RoleViewModel getEditor(String id) {
        return viewModel.load(repository
                .findById(new RoleId(id))
                .orElseThrow());
    }

    @Override
    public RoleViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
