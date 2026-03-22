package io.mateu.workflow.usersservice.domain.aggregates.user;


import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Email;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Status;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor@NoArgsConstructor
@Getter
public class User extends AggregateRoot {

    UserId id;

    Name name;

    Email email;

    Status status;

    List<UserGroupId> groups;

    List<RoleId> roles;

    public static User of(UserId userId, Name name, Email email, List<UserGroupId> groups, List<RoleId> roles) {
        return new User(userId, name, email, Status.Active, groups, roles);
    }

    public void update(Name name, Email email, List<UserGroupId> groups, List<RoleId> roles) {
        this.name = name;
        this.email = email;
        this.groups = groups;
        this.roles = roles;
    }

}
