package io.mateu.workflow.usersservice.domain.aggregates.role;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Role extends AggregateRoot {

    RoleId id;

    Name name;

    Description description;

    List<PermissionId> permissions;

    public static Role of(RoleId id, Name name, Description description, List<PermissionId> permissions) {
        return new Role(id, name, description, permissions);
    }

    public void update(Name name, Description description, List<PermissionId> permissions) {
        this.name = name;
        this.description = description;
        this.permissions = permissions;
    }
}
