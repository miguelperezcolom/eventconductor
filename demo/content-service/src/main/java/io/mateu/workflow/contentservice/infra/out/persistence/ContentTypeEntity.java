package io.mateu.workflow.contentservice.infra.out.persistence;


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
public class ContentTypeEntity {

@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "contenttype_seq_gen")
@SequenceGenerator(
name = "contenttype_seq_gen",
sequenceName = "contenttype_sequence",
allocationSize = 1
)
Long id;

String name;

}
