package io.mateu.workflow.usersservice.domain.aggregates.permission;


import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.Scope;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor@NoArgsConstructor
@Getter
public class Permission extends AggregateRoot {

    PermissionId id;

    Name name;

    Description description;

    Scope scope;

    public static Permission of(Name name, Description description, Scope scope) {
        Permission p = new Permission();
        p.name = name;
        p.description = description;
        p.scope = scope;
        return p;
    }

    public void update(Name name, Description description, Scope scope) {
        this.name = name;
        this.description = description;
        this.scope = scope;
    }
}
