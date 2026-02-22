package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Entity
@Getter@Setter
public class StepEntity {
    @Id
    private String id;

    String workflowDefinitionId;

    String name;

    String description;

    String variables;

}
