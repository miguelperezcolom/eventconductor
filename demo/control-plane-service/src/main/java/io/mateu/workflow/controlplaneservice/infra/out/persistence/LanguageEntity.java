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
public class LanguageEntity {

@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "language_seq_gen")
@SequenceGenerator(
name = "language_seq_gen",
sequenceName = "language_sequence",
allocationSize = 1
)
Long id;

String name;

}
