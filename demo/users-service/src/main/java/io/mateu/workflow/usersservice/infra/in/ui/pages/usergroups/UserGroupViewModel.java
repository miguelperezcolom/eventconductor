package io.mateu.workflow.usersservice.infra.in.ui.pages.usergroups;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.usersservice.application.query.dto.UserGroupDto;
import io.mateu.workflow.usersservice.application.usecases.usergroup.create.CreateUserGroupCommand;
import io.mateu.workflow.usersservice.application.usecases.usergroup.create.CreateUserGroupUseCase;
import io.mateu.workflow.usersservice.application.usecases.usergroup.save.SaveUserGroupCommand;
import io.mateu.workflow.usersservice.application.usecases.usergroup.save.SaveUserGroupUseCase;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.UserGroup;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class UserGroupViewModel implements Identifiable {
    @EditableOnlyWhenCreating
            @NotEmpty
    String id;
    @NotEmpty String name;
    String description;

    final CreateUserGroupUseCase createUserGroupUseCase;
    final SaveUserGroupUseCase saveUserGroupUseCase;

    public String create(HttpRequest httpRequest) {
        createUserGroupUseCase.handle(new CreateUserGroupCommand(id, name, description));
        return id;
    }

    public void save(HttpRequest httpRequest) {
        saveUserGroupUseCase.handle(new SaveUserGroupCommand(id, name, description));
    }

    @Override
    public String id() {
        return id;
    }

    public UserGroupViewModel load(UserGroupDto userGroup) {
        id = userGroup.id();
        name = userGroup.name();
        description = userGroup.description();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New user group";
    }
}
