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
 * A synchronous invocation (see {@code Invocation}). The unique constraint on
 * {@code (workflow_definition_id, idempotency_key)} <em>is</em> the idempotency: of two concurrent
 * requests with one key, exactly one insert commits. Declared here as well as in
 * {@code V32__sync_invocation.sql}, for the schemas {@code ddl-auto} creates.
 */
@Entity
@Table(name = "sync_invocation",
        uniqueConstraints = @UniqueConstraint(name = "uk_sync_invocation_key",
                columnNames = {"workflow_definition_id", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_sync_invocation_expires", columnList = "expires_at"),
                @Index(name = "idx_sync_invocation_process", columnList = "process_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvocationEntity {

    @Id
    private String id;

    @Column(name = "workflow_definition_id", nullable = false)
    private String workflowDefinitionId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "process_id", nullable = false)
    private String processId;

    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "caller_trace_parent", length = 64)
    private String callerTraceParent;
}
