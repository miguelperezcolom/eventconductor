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
public class LabelEntity {

@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "label_seq_gen")
@SequenceGenerator(
name = "label_seq_gen",
sequenceName = "label_sequence",
allocationSize = 1
)
Long id;

String name;

}
