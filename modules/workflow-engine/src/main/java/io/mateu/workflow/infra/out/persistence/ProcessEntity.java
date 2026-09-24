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
}
