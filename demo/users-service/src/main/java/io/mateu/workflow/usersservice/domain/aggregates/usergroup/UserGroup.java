package io.mateu.workflow.usersservice.domain.aggregates.usergroup;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Status;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

@With
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

}
