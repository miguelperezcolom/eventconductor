package io.mateu.workflow.usersservice.domain.aggregates.role;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;

@With
@NoArgsConstructor@AllArgsConstructor
@Getter
public class Role extends AggregateRoot implements Identifiable {

    RoleId id;

    Name name;

    Description description;

    List<PermissionId> permissions;

    public static Role of(RoleId id, Name name, Description description, List<PermissionId> permissions) {
        return new Role(id, name, description, permissions);
    }

    @Override
    public String id() {
        return id.id();
    }
}
