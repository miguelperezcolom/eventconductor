package io.mateu.workflow.infra.out.persistence;

/**
 * The tables of flow control and long-lived processes (V56), read and written with plain JDBC
 * ({@link JdbcFlowControlStore}, {@link JdbcRateLimitStore}) and mapped as
 * entities only so a schema {@code ddl-auto} builds has them — as {@link HttpTaskEntity} is for its table.
 * Mirrors {@code V56__flow_control_and_long_lived_processes.sql}.
 */
public final class FlowControlEntities {

    private FlowControlEntities() {
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "flow_control_key")
    @lombok.Getter
    @lombok.Setter
    @lombok.NoArgsConstructor
    public static class FlowControlKeyEntity {
        @jakarta.persistence.Id
        @jakarta.persistence.Column(name = "id", length = 64)
        private String id;
        @jakarta.persistence.Column(name = "scope", length = 512, nullable = false)
        private String scope;
        @jakarta.persistence.Column(name = "flow_key", length = 1024)
        private String flowKey;
        @jakarta.persistence.Column(name = "window_start")
        private java.time.LocalDateTime windowStart;
        @jakarta.persistence.Column(name = "window_count", nullable = false)
        private int windowCount;
        @jakarta.persistence.Column(name = "last_process_id")
        private String lastProcessId;
        @jakarta.persistence.Column(name = "last_business_key")
        private String lastBusinessKey;
        @jakarta.persistence.Column(name = "last_started_at")
        private java.time.LocalDateTime lastStartedAt;
        @jakarta.persistence.Column(name = "updated_at", nullable = false)
        private java.time.LocalDateTime updatedAt;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "flow_control_pending", indexes = {
            @jakarta.persistence.Index(name = "idx_flow_control_pending_due", columnList = "due_at"),
            @jakarta.persistence.Index(name = "idx_flow_control_pending_key", columnList = "key_id, kind")
    })
    @lombok.Getter
    @lombok.Setter
    @lombok.NoArgsConstructor
    public static class FlowControlPendingEntity {
        @jakarta.persistence.Id
        @jakarta.persistence.Column(name = "id", length = 64)
        private String id;
        @jakarta.persistence.Column(name = "key_id", length = 64, nullable = false)
        private String keyId;
        @jakarta.persistence.Column(name = "kind", length = 16, nullable = false)
        private String kind;
        @jakarta.persistence.Column(name = "workflow_id", nullable = false)
        private String workflowId;
        @jakarta.persistence.Column(name = "request_json", columnDefinition = "TEXT", nullable = false)
        private String requestJson;
        @jakarta.persistence.Column(name = "due_at", nullable = false)
        private java.time.LocalDateTime dueAt;
        @jakarta.persistence.Column(name = "enqueued_at", nullable = false)
        private java.time.LocalDateTime enqueuedAt;
        @jakarta.persistence.Column(name = "absorbed", nullable = false)
        private int absorbed;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "rate_limit_window")
    @lombok.Getter
    @lombok.Setter
    @lombok.NoArgsConstructor
    public static class RateLimitWindowEntity {
        @jakarta.persistence.Id
        @jakarta.persistence.Column(name = "topic")
        private String topic;
        @jakarta.persistence.Column(name = "window_start", nullable = false)
        private java.time.LocalDateTime windowStart;
        @jakarta.persistence.Column(name = "used", nullable = false)
        private int used;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "deferred_dispatch", indexes =
            @jakarta.persistence.Index(name = "idx_deferred_dispatch_topic", columnList = "topic, priority, deferred_at"))
    @lombok.Getter
    @lombok.Setter
    @lombok.NoArgsConstructor
    public static class DeferredDispatchEntity {
        @jakarta.persistence.Id
        @jakarta.persistence.Column(name = "step_execution_id")
        private String stepExecutionId;
        @jakarta.persistence.Column(name = "process_id", nullable = false)
        private String processId;
        @jakarta.persistence.Column(name = "topic", nullable = false)
        private String topic;
        @jakarta.persistence.Column(name = "priority", nullable = false)
        private int priority;
        @jakarta.persistence.Column(name = "deferred_at", nullable = false)
        private java.time.LocalDateTime deferredAt;
    }
}
