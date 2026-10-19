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
// Declared here as well as in V45, so a schema ddl-auto creates has the purge's indexes too.
@jakarta.persistence.Table(name = "process_entity", indexes = {
        @jakarta.persistence.Index(name = "idx_process_released_business_key", columnList = "releasedBusinessKey"),
        @jakarta.persistence.Index(name = "idx_process_retention", columnList = "workflowDefinitionId, status, finished"),
        @jakarta.persistence.Index(name = "idx_process_parent_step", columnList = "parentStepExecutionId")
})
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class ProcessEntity {
    @Id
    private String id;

    // UNIQUE, and it stays so: it names the run that holds the key now. A run that handed its key over to a
    // later one (CONTINUE_AS_NEW, a businessKeyPolicy reuse) keeps it in releasedBusinessKey instead (V56) —
    // which is what keeps the constraint every older engine relies on during a rolling upgrade.
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

    /**
     * Optimistic-locking version. Boxed on purpose: Spring Data reads a null version as "never
     * persisted" and inserts, which is what keeps assigned ids working without a separate
     * existence check.
     */
    @jakarta.persistence.Version
    private Integer version;

    /** The shape before a process could reply: callers that build rows by hand keep compiling. */
    public ProcessEntity(String id, String businessKey, String name, String variables, String status,
                         int completionPercentage, String log, String workflowDefinitionId,
                         int workflowDefinitionVersion, String workflowDefinitionJson,
                         LocalDateTime created, LocalDateTime started, LocalDateTime finished,
                         LocalDateTime pausedAt, String parentStepExecutionId, Integer version) {
        this(id, businessKey, name, variables, status, completionPercentage, log, workflowDefinitionId,
                workflowDefinitionVersion, workflowDefinitionJson, created, started, finished, pausedAt,
                parentStepExecutionId, version, null, null, null, null, null, null);
    }

    /** The reply given to a synchronous caller (see {@code ProcessReply}); all null until then. */
    private String replyStepId;
    @Column(columnDefinition = "TEXT")
    private String replyJson;
    @Column(length = 40)
    private String replyOutcome;
    @Column(length = 40)
    private String replyCompensation;
    @Column(columnDefinition = "TEXT")
    private String replyError;
    private LocalDateTime repliedAt;

    /** CONTINUE_AS_NEW: the run this one continues (V56). */
    private String continuedFromProcessId;
    /** CONTINUE_AS_NEW: the run that continues this one (V56). */
    private String continuedAsProcessId;

    /** The business key of a run that handed it over to a later run; null while it holds it (V56). */
    private String releasedBusinessKey;

    /** The row before CONTINUE_AS_NEW (V56): no links. */
    public ProcessEntity(String id, String businessKey, String name, String variables, String status,
                         int completionPercentage, String log, String workflowDefinitionId,
                         int workflowDefinitionVersion, String workflowDefinitionJson,
                         LocalDateTime created, LocalDateTime started, LocalDateTime finished,
                         LocalDateTime pausedAt, String parentStepExecutionId, Integer version,
                         String replyStepId, String replyJson, String replyOutcome, String replyCompensation,
                         String replyError, LocalDateTime repliedAt) {
        this(id, businessKey, name, variables, status, completionPercentage, log, workflowDefinitionId,
                workflowDefinitionVersion, workflowDefinitionJson, created, started, finished, pausedAt,
                parentStepExecutionId, version, replyStepId, replyJson, replyOutcome, replyCompensation, replyError,
                repliedAt, null, null, null);
    }
}
