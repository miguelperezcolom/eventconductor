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
        @Index(name = "idx_step_exec_awaiting_message", columnList = "awaitingMessageName, awaitingCorrelationKey"),
        // The lease reaper finds a lock's waiting/holding steps by the key they carry.
        @Index(name = "idx_step_exec_lock", columnList = "lockName, lockKey, status")
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

    /**
     * The step's type, lifted out of {@link #stepJson} so it can be queried.
     *
     * <p>Only the stalled-step count needs it, and needs it for a reason worth the column: whether
     * a live step with no deadline is a problem or the entire point depends on what kind of step
     * it is, and asking that question of a JSON blob is not a query any database can serve. Null
     * on rows written before this column existed — see the count query for what that means.
     */
    String stepType;

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
     * The named lock a step in {@code WAITING_ON_LOCK} (or holding one) is bound to, lifted out so
     * the lease reaper can find it and so re-entry after a restart is idempotent. Null for every
     * step that is not a lock step. Wired by the engine in P3.
     */
    String lockName;

    String lockKey;

    /**
     * Optimistic-locking version. Boxed on purpose: Spring Data reads a null version as "never
     * persisted" and inserts, which is what keeps assigned ids working without a separate
     * existence check.
     */
    @jakarta.persistence.Version
    Integer version;

    /**
     * The id of the DYNAMIC step execution that injected this row at runtime, or null for a step
     * created the ordinary way from the definition. Nullable and unindexed: it is read only when
     * walking a single process's steps (which is already an indexed lookup), never queried across
     * processes.
     */
    String injectedByStepExecutionId;

    /**
     * Backward-compatible constructor for the callers that predate the lock columns (the write-side
     * mapping in {@code StepExecutionDBRepository}). They arrive null; only lock steps set them,
     * once the engine wires that in. Kept as an overload rather than reordering the fields, the same
     * way {@code Step} evolves its constructors.
     */
    public StepExecutionEntity(String id, String processId, String workflowDefinitionId, String stepId,
            String stepJson, String stepType, String variables, String status, String workerId, long order,
            LocalDateTime startedAt, LocalDateTime finishedAt, int attemptCount, LocalDateTime deadlineAt,
            String awaitingMessageName, String awaitingCorrelationKey, Integer version,
            String injectedByStepExecutionId) {
        this(id, processId, workflowDefinitionId, stepId, stepJson, stepType, variables, status, workerId,
                order, startedAt, finishedAt, attemptCount, deadlineAt, awaitingMessageName,
                awaitingCorrelationKey, null, null, version, injectedByStepExecutionId);
    }

}
