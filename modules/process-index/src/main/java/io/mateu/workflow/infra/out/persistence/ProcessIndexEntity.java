package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The CQRS process-index read model, as a table. Written only by the projector; every listing /
 * lookup / count query reads it instead of scanning the write tables. Indexed for exactly those
 * queries: by status ("what is running"), by (definition, status), and by business key (the unique
 * routing key).
 */
@Entity
@Table(name = "process_index", indexes = {
        @Index(name = "idx_process_index_status", columnList = "status"),
        @Index(name = "idx_process_index_def_status", columnList = "workflowDefinitionId, status"),
        @Index(name = "idx_process_index_business_key", columnList = "businessKey")
})
public class ProcessIndexEntity {

    @Id
    private String processId;
    private String businessKey;

    /**
     * The process's own name — what the operator listing shows and searches by. Null on rows
     * projected before it was carried, which the listing handles rather than showing a blank.
     */
    @Column(columnDefinition = "TEXT")
    private String name;
    private String workflowDefinitionId;
    private int workflowDefinitionVersion;
    private String status;
    private int completionPercentage;
    private LocalDateTime created;
    private LocalDateTime started;
    private LocalDateTime finished;
    private LocalDateTime updatedAt;
    private String shardId;

    protected ProcessIndexEntity() {
    }

    public ProcessIndexEntity(String processId, String businessKey, String name, String workflowDefinitionId,
                              int workflowDefinitionVersion, String status, int completionPercentage,
                              LocalDateTime created, LocalDateTime started, LocalDateTime finished,
                              LocalDateTime updatedAt, String shardId) {
        this.processId = processId;
        this.businessKey = businessKey;
        this.name = name;
        this.workflowDefinitionId = workflowDefinitionId;
        this.workflowDefinitionVersion = workflowDefinitionVersion;
        this.status = status;
        this.completionPercentage = completionPercentage;
        this.created = created;
        this.started = started;
        this.finished = finished;
        this.updatedAt = updatedAt;
        this.shardId = shardId;
    }

    public String getProcessId() {
        return processId;
    }

    public String getName() {
        return name;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getWorkflowDefinitionId() {
        return workflowDefinitionId;
    }

    public int getWorkflowDefinitionVersion() {
        return workflowDefinitionVersion;
    }

    public String getStatus() {
        return status;
    }

    public int getCompletionPercentage() {
        return completionPercentage;
    }

    public LocalDateTime getCreated() {
        return created;
    }

    public LocalDateTime getStarted() {
        return started;
    }

    public LocalDateTime getFinished() {
        return finished;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getShardId() {
        return shardId;
    }
}
