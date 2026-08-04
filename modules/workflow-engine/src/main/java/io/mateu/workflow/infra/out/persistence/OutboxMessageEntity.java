package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.ddd.DomainEvent;
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
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.toJson;

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
@Table(name = "outbox_message_entity", indexes = {
        // The relays claim pending messages oldest first, so the ordering has to be covered too.
        @Index(name = "idx_outbox_status_ts", columnList = "status, timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessageEntity {

    @Id
    private String id;

    private LocalDateTime timestamp;

    private String status;

    private String messageType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    /**
     * The W3C {@code traceparent} of whatever produced this event, so the relay can publish it as
     * part of that trace instead of starting a new one.
     *
     * <p>This row is the engine's asynchronous boundary: written inside one transaction, published
     * by another thread later. Nothing automatic bridges that gap — the instrumentation sees a
     * database write in one trace and, some time afterwards, an unrelated Kafka send — so without
     * carrying the context here, following a process end to end gives a trace per hop. Null when
     * nothing was being traced, which is the normal case.
     */
    @Column(length = 64)
    private String traceParent;

    /** An event with no trace attached — what happens when tracing is off, which is the default. */
    public OutboxMessageEntity(DomainEvent event) {
        this(event, null);
    }

    public OutboxMessageEntity(DomainEvent event, String traceParent) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
        this.status = OutboxMessageStatus.Pending.name();
        this.messageType = event.getClass().getName();
        this.payload = toJson(event);
        this.traceParent = traceParent;
    }
}
