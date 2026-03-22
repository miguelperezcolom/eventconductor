package io.mateu.workflow.usersservice.infra.out.persistence;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor@NoArgsConstructor
public class RoleEntity {

    @Id
    String id;

    String name;

    String description;

    String permissionsJson;

}
