package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.WorkflowDefinitionStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter@Setter
public class WorkflowDefinitionEntity {
    @Id
    private String id;

    String name;

    String description;

    int version;

    String status;

}
