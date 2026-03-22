package io.mateu.workflow.usersservice.infra.in.ui.pages.users;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.usersservice.application.query.dto.UserRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Users")
public class UsersCrudOrchestrator extends CrudOrchestrator<
        UserViewModel,
        UserViewModel,
        UserViewModel,
        NoFilters,
        UserRow,
        String
        > {

    final UserCrudAdapter adapter;

    @Override
    public CrudAdapter<UserViewModel,
            UserViewModel, UserViewModel,
            NoFilters, UserRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
