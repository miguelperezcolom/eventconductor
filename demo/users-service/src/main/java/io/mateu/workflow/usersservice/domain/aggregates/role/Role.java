package io.mateu.workflow.usersservice.domain.aggregates.role;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Description;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;

@With
@NoArgsConstructor@AllArgsConstructor
@Getter
public class Role extends AggregateRoot implements Identifiable {

    RoleId id;

    Name name;

    Description description;

    public static Role of(RoleId id, Name name, Description description) {
        return new Role(id, name, description);
    }

    @Override
    public String id() {
        return id.id();
    }
}
