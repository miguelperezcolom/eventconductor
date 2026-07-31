package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class ProcessEntity {
    @Id
    private String id;

    @Column(unique = true)
    private String businessKey;

    private String name;

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

    private LocalDateTime created;
    private LocalDateTime started;
    private LocalDateTime finished;

    /** Set while the process is PAUSED: the moment it was paused. Null otherwise. */
    private LocalDateTime pausedAt;

    /** Parent PROCESS step execution that spawned this process; null for top-level processes. */
    private String parentStepExecutionId;
}
