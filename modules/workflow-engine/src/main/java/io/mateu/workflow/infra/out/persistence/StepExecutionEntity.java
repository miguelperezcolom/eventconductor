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
public class StepExecutionEntity {
    @Id
    private String id;

    String processId;

    String workflowDefinitionId;

    String stepId;

    @Column(columnDefinition = "TEXT")
    String stepJson;

    @Column(columnDefinition = "TEXT")
    String  variables;

    String status;

    String workerId;

    @Column(name = "_order")
    long order;

    LocalDateTime startedAt;

    LocalDateTime finishedAt;

    int attemptCount;

    LocalDateTime deadlineAt;

    String awaitingMessageName;

    String awaitingCorrelationKey;

    /**
     * Optimistic-locking version. Boxed on purpose: Spring Data reads a null version as "never
     * persisted" and inserts, which is what keeps assigned ids working without a separate
     * existence check.
     */
    @jakarta.persistence.Version
    Integer version;

}
