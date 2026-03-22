package io.mateu.workflow.usersservice.domain.aggregates.user;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Email;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Status;
import io.mateu.workflow.usersservice.domain.aggregates.user.vo.UserId;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;

@With
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

}
