package io.mateu.workflow.infra.out.persistence;

import io.mateu.uidl.annotations.Hidden;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class WorkflowDefinitionEntity {
    @Id
    private String id;

    String name;

    int version;

    String description;

    String status;

    @Column(columnDefinition = "TEXT")
    String stepsJson;

    boolean limitConcurrentExecutions;

    int maxConcurrentExecutions;

    boolean enqueueOnLimit;

}
