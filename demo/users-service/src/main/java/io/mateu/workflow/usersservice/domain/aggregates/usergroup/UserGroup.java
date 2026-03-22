package io.mateu.workflow.usersservice.domain.aggregates.usergroup;


import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Status;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class UserGroup extends AggregateRoot {

    UserGroupId id;

    Name name;

    Description description;

    Status status;

    public static UserGroup of(UserGroupId id, Name name, Description description) {
        return new UserGroup(id, name, description, Status.Active);
    }

    public void update(Name name, Description description) {
        this.name = name;
        this.description = description;
    }
}
