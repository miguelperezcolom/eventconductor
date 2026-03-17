package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class ProcessEntity {
    @Id
    private String id;

    private String businessKey;

    @Column(columnDefinition = "TEXT")
    private String variables;

    private String status;

    private int completionPercentage;

    @Column(columnDefinition = "TEXT")
    private String log;

    private String workflowDefinitionId;
    private int workflowDefinitionVersion;
    @Column(columnDefinition = "TEXT")
    private String workflowDefinitionJson;
}
