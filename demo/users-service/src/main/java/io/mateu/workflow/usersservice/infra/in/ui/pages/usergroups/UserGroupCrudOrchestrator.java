package io.mateu.workflow.usersservice.infra.in.ui.pages.usergroups;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.usersservice.application.query.dto.UserGroupRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("User Groups")
public class UserGroupCrudOrchestrator extends CrudOrchestrator<
        UserGroupViewModel,
        UserGroupViewModel,
        UserGroupViewModel,
        NoFilters,
        UserGroupRow,
        String
        > {

    final UserGroupCrudAdapter adapter;

    @Override
    public CrudAdapter<UserGroupViewModel,
            UserGroupViewModel, UserGroupViewModel,
            NoFilters, UserGroupRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
