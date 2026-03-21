package io.mateu.workflow.usersservice.infra.in.ui.pages.permissions;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Permissions")
public class PermissionsCrudOrchestrator extends CrudOrchestrator<
        PermissionViewModel,
        PermissionViewModel,
        PermissionViewModel,
        NoFilters,
        PermissionRow,
        String
        > {

    final PermissionCrudAdapter adapter;

    @Override
    public CrudAdapter<PermissionViewModel,
            PermissionViewModel, PermissionViewModel,
            NoFilters, PermissionRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
