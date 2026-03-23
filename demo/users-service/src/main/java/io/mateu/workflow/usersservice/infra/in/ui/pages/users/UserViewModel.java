package io.mateu.workflow.usersservice.infra.in.ui.pages.users;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.annotations.ForeignKey;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.usersservice.application.query.dto.UserDto;
import io.mateu.workflow.usersservice.application.usecases.user.create.CreateUserCommand;
import io.mateu.workflow.usersservice.application.usecases.user.create.CreateUserUseCase;
import io.mateu.workflow.usersservice.application.usecases.user.save.SaveUserCommand;
import io.mateu.workflow.usersservice.application.usecases.user.save.SaveUserUseCase;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.user.User;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import io.mateu.workflow.usersservice.infra.in.ui.suppliers.RoleIdLabelSupplier;
import io.mateu.workflow.usersservice.infra.in.ui.suppliers.RoleIdOptionsSupplier;
import io.mateu.workflow.usersservice.infra.in.ui.suppliers.UserGroupIdLabelSupplier;
import io.mateu.workflow.usersservice.infra.in.ui.suppliers.UserGroupIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class UserViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @EditableOnlyWhenCreating
            @NotEmpty
    String id;
    @NotEmpty String name;
    String email;
    @ForeignKey(search = UserGroupIdOptionsSupplier.class, label = UserGroupIdLabelSupplier.class)
    List<String> groups;
    @ForeignKey(search = RoleIdOptionsSupplier.class, label = RoleIdLabelSupplier.class)
    List<String> roles;

    final CreateUserUseCase createUserUseCase;
    final SaveUserUseCase saveUserUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        createUserUseCase.handle(new CreateUserCommand(id, name, email, groups, roles));
        return id;
    }

    @Override
    public void save(HttpRequest httpRequest) {
        saveUserUseCase.handle(new SaveUserCommand(id, name, email, groups, roles));
    }

    @Override
    public String id() {
        return id;
    }

    public UserViewModel load(UserDto user) {
        id = user.id();
        name = user.name();
        email = user.email();
        roles = user.roleIds();
        groups = user.groupIds();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New user";
    }
}
