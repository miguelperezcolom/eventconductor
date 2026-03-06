package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter@Setter
public class WorkflowDefinitionVersionEntity {
    @Id
    private String id;

    String workflowDefinitionId;

    int version;

    String json;

}
