package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
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
@Column(columnDefinition = "TEXT")

    String precondition;

    String name;
@Column(columnDefinition = "TEXT")

    String description;
@Column(columnDefinition = "TEXT")

    String variables;

    boolean compensable;

    long timeout;

    int retries;

    String compensationStepId;

}
