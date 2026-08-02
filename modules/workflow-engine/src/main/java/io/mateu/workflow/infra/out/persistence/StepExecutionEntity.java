package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <p>The indexes are declared here as well as in the Flyway migrations, and that duplication is
 * deliberate. A schema created by {@code ddl-auto} — which is what the embedded path and every
 * test harness use — gets tables from the entities and nothing from the migrations, so without
 * this the engine runs with primary keys only. Every deadline scan, outbox claim and correlation
 * lookup then becomes a sequential scan, and the queries this engine was built around stop being
 * lookups at all. Measured on a cluster where they were missing: PostgreSQL pinned at 750m of CPU
 * and throughput down from tens of process instances a second to about one.
 */
@Entity
@Table(name = "step_execution_entity", indexes = {
        // The timer/timeout scheduler asks for live steps whose deadline has passed.
        @Index(name = "idx_step_exec_deadline", columnList = "deadlineAt"),
        // The timer and timeout checks look up the live steps of one process.
        @Index(name = "idx_step_exec_process_status", columnList = "processId, status"),
        // The scheduler still lists live steps system-wide at boot.
        @Index(name = "idx_step_exec_status", columnList = "status"),
        // An arriving message finds its subscribers by name and correlation key.
        @Index(name = "idx_step_exec_awaiting_message", columnList = "awaitingMessageName, awaitingCorrelationKey")
})
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
