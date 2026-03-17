package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class FieldEntity {
    @Id
    private String id;

    private String formId;

    private String label;

    private String dataType;

    private String stereotype;

    private boolean required;

    private String description;

}
