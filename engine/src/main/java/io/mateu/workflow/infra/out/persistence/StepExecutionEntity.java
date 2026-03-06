package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter@Setter
public class StepExecutionEntity {
    @Id
    private String id;

    String workflowDefinitionId;

    String stepId;

    String  variables;

    String status;

    String workerId;

}
