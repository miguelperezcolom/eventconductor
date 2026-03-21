package io.mateu.workflow.usersservice.infra.out.persistence;


import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor@NoArgsConstructor
public class PermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "permission_seq_gen")
    @SequenceGenerator(
            name = "permission_seq_gen",
            sequenceName = "permission_sequence",
            allocationSize = 1
    )
    Long id;

    String name;

    String description;

}
