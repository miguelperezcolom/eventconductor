package io.mateu.workflow.contentservice.infra.out.persistence;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor@NoArgsConstructor
@Getter
public class ContentEntity {

@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "content_seq_gen")
@SequenceGenerator(
name = "content_seq_gen",
sequenceName = "content_sequence",
allocationSize = 1
)
Long id;

String name;

Long contentTypeId;

    @Column(columnDefinition = "TEXT")
String labelsJson;

@Column(columnDefinition = "TEXT")
String valuesJson;

}
