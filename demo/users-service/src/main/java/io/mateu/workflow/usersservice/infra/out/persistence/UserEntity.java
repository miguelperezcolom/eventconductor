package io.mateu.workflow.usersservice.infra.out.persistence;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor@NoArgsConstructor
public class UserEntity {

    @Id
    String id;

    String name;

    String email;

    @Column(columnDefinition = "TEXT")
    String groupsJson;

    @Column(columnDefinition = "TEXT")
    String rolesJson;

    String status;

}
