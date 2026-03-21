package io.mateu.workflow.usersservice.infra.in.ui.pages.permissions;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class PermissionCrudAdapter implements CrudAdapter<
        PermissionViewModel,
        PermissionViewModel,
        PermissionViewModel,
        NoFilters,
        PermissionRow,
        String
        > {

    final PermissionViewModel viewModel;
    final PermissionRepository repository;

    @Override
    public ListingData<PermissionRow> search(String searchText,
                                             NoFilters filters,
                                             Pageable pageable) {
        var data = repository.findAll(searchText, filters, pageable);
        return new ListingData(new Page(searchText, data.page().pageSize(),
                data.page().pageNumber(),
                data.page().totalElements(), data.page().content().stream()
                .map(permission -> new PermissionRow(
                        permission.getId().id().toString(),
                        permission.getName().name(),
                        permission.getDescription().description(),
                        permission.getScope().scope()))
                .toList()));
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        repository.deleteAllById(selectedIds.stream()
                .map(Long::valueOf)
                .map(PermissionId::new)
                .toList());
    }

    @Override
    public PermissionViewModel getView(String id) {
        return viewModel.load(repository
                .findById(new PermissionId(Long.valueOf(id)))
                .orElseThrow());
    }

    @Override
    public PermissionViewModel getEditor(String id) {
        return viewModel.load(repository
                .findById(new PermissionId(Long.valueOf(id)))
                .orElseThrow());
    }

    @Override
    public PermissionViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
