package io.mateu.workflow.usersservice.infra.in.ui.pages.roles;

import io.mateu.uidl.annotations.EditableOnlyWhenCreating;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.usersservice.application.usecases.permission.create.CreatePermissionCommand;
import io.mateu.workflow.usersservice.application.usecases.permission.create.CreatePermissionUseCase;
import io.mateu.workflow.usersservice.application.usecases.permission.save.SavePermissionCommand;
import io.mateu.workflow.usersservice.application.usecases.permission.save.SavePermissionUseCase;
import io.mateu.workflow.usersservice.application.usecases.role.create.CreateRoleCommand;
import io.mateu.workflow.usersservice.application.usecases.role.create.CreateRoleUseCase;
import io.mateu.workflow.usersservice.application.usecases.role.save.SaveRoleCommand;
import io.mateu.workflow.usersservice.application.usecases.role.save.SaveRoleUseCase;
import io.mateu.workflow.usersservice.domain.aggregates.permission.Permission;
import io.mateu.workflow.usersservice.domain.aggregates.role.Role;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class RoleViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @EditableOnlyWhenCreating
    @NotEmpty
    String id;
    @NotEmpty String name;
    String description;

    final CreateRoleUseCase createPermissionUseCase;
    final SaveRoleUseCase savePermissionUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        createPermissionUseCase.handle(new CreateRoleCommand(id, name, description));
        return id;
    }

    @Override
    public void save(HttpRequest httpRequest) {
        savePermissionUseCase.handle(new SaveRoleCommand(id, name, description));
    }

    @Override
    public String id() {
        return id;
    }

    public RoleViewModel load(Role permission) {
        id = permission.getId().id();
        name = permission.getName().name();
        description = permission.getDescription().description();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New role";
    }
}
