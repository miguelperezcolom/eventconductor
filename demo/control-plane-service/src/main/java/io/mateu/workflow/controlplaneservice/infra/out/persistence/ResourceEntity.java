package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor@NoArgsConstructor
@Getter
public class ResourceEntity {

@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "resource_seq_gen")
@SequenceGenerator(
name = "resource_seq_gen",
sequenceName = "resource_sequence",
allocationSize = 1
)
Long id;

String name;

}
