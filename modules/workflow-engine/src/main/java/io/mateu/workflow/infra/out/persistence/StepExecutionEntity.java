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
public class StepExecutionEntity {
    @Id
    private String id;

    String processId;

    String workflowDefinitionId;

    String stepId;

    String stepJson;

    String  variables;

    String status;

    String workerId;

}
