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
@Table(name = "log_message_entity", indexes = {
        @Index(name = "idx_log_process", columnList = "processId")
})
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class LogMessageEntity {
    @Id
    private String id;

    private LocalDateTime timestamp;

    private String processId;

    private String stepExecutionId;

    private String messageType;

    @Column(columnDefinition = "TEXT")
    private String message;

    private String workerId;

}
