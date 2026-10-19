package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A task queued for an HTTP worker (see {@code HttpTaskQueue}). Read and written with plain JDBC by
 * {@link JdbcHttpTaskQueue} — the claim needs {@code FOR UPDATE SKIP LOCKED} — and mapped here only so
 * a schema {@code ddl-auto} builds has the table. Mirrors {@code V50__http_task.sql}.
 */
@Entity
@Table(name = "http_task", indexes = {
        @Index(name = "idx_http_task_topic_created", columnList = "topic, created_at"),
        @Index(name = "idx_http_task_lease_until", columnList = "lease_until")
})
@Getter
@Setter
@NoArgsConstructor
public class HttpTaskEntity {

    @Id
    private String id;

    @Column(name = "process_id")
    private String processId;

    @Column(name = "workflow_definition_id")
    private String workflowDefinitionId;

    @Column(name = "step_id")
    private String stepId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Column(name = "attempt")
    private Integer attempt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "deliveries", nullable = false)
    private int deliveries;

    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    /** 0–100, higher first (V56); claimed ORDER BY priority DESC, created_at. */
    @Column(name = "priority", nullable = false)
    @org.hibernate.annotations.ColumnDefault("50")
    private int priority = 50;
}
