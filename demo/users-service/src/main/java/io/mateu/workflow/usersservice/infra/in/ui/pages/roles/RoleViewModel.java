package io.mateu.workflow.usersservice.infra.in.ui.pages.roles;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.usersservice.application.query.dto.RoleDto;
import io.mateu.workflow.usersservice.application.usecases.role.create.CreateRoleCommand;
import io.mateu.workflow.usersservice.application.usecases.role.create.CreateRoleUseCase;
import io.mateu.workflow.usersservice.application.usecases.role.save.SaveRoleCommand;
import io.mateu.workflow.usersservice.application.usecases.role.save.SaveRoleUseCase;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.Role;
import io.mateu.workflow.usersservice.infra.in.ui.suppliers.PermissionIdLabelSupplier;
import io.mateu.workflow.usersservice.infra.in.ui.suppliers.PermissionIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class RoleViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @EditableOnlyWhenCreating
    @NotEmpty
    String id;
    @NotEmpty String name;
    @Colspan(2)
    @Style("width: 100%;")
    String description;
    @Lookup(search = PermissionIdOptionsSupplier.class, label = PermissionIdLabelSupplier.class)
    //@Stereotype(FieldStereotype.checkbox)
            @Colspan(2)
            @Style("width: 100%;")
            @Stereotype(FieldStereotype.checkbox)
    List<String> permissions;

    final CreateRoleUseCase createRoleUseCase;
    final SaveRoleUseCase saveRoleUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        createRoleUseCase.handle(new CreateRoleCommand(id, name, description, permissions));
        return id;
    }

    @Override
    public void save(HttpRequest httpRequest) {
        saveRoleUseCase.handle(new SaveRoleCommand(id, name, description, permissions));
    }

    @Override
    public String id() {
        return id;
    }

    public RoleViewModel load(RoleDto role) {
        id = role.id();
        name = role.name();
        description = role.description();
        permissions = role.permissionIds();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New role";
    }
}
