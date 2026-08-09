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

    String type;

    String precondition;

    String name;

    String description;

    String variables;

    boolean compensable;

    long timeout;

    int retries;

    String compensationStepId;

}
