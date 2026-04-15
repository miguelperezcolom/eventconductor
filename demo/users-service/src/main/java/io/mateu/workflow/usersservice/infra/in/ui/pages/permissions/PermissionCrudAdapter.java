package io.mateu.workflow.usersservice.infra.in.ui.pages.permissions;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.usersservice.application.out.PermissionRepository;
import io.mateu.workflow.usersservice.application.query.PermissionQueryService;
import io.mateu.workflow.usersservice.application.query.dto.PermissionRow;
import io.mateu.workflow.usersservice.application.usecases.permission.delete.DeletePermissionCommand;
import io.mateu.workflow.usersservice.application.usecases.permission.delete.DeletePermissionUseCase;
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
    final DeletePermissionUseCase deletePermissionUseCase;
    final PermissionQueryService queryService;

    @Override
    public ListingData<PermissionRow> search(String searchText,
                                             NoFilters filters,
                                             Pageable pageable, HttpRequest httpRequest) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deletePermissionUseCase.handle(new DeletePermissionCommand(selectedIds));
    }

    @Override
    public PermissionViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public PermissionViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public PermissionViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
