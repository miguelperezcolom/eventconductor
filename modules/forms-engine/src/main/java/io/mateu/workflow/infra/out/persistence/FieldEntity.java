package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter@Setter
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
