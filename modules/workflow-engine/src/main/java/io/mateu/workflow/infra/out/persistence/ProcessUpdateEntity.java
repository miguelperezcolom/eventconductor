package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A process update (see {@code ProcessUpdate}). The unique constraint on {@code (process_id,
 * idempotency_key)} is the idempotency: of two concurrent requests with one key exactly one insert
 * commits. Declared here as well as in {@code V55__process_update.sql}, for the schemas {@code ddl-auto}
 * creates.
 */
@Entity
@Table(name = "process_update",
        uniqueConstraints = @UniqueConstraint(name = "uk_process_update_key",
                columnNames = {"process_id", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_process_update_process", columnList = "process_id"),
                @Index(name = "idx_process_update_expires", columnList = "expires_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessUpdateEntity {

    @Id
    private String id;

    @Column(name = "process_id", nullable = false)
    private String processId;

    @Column(name = "workflow_definition_id")
    private String workflowDefinitionId;

    @Column(name = "update_name", nullable = false)
    private String name;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "with_start", nullable = false)
    private boolean withStart;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
