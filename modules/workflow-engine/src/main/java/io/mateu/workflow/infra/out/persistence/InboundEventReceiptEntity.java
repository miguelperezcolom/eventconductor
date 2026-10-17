package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One receipt of an inbound domain event (see {@code EventReceiptRepository}). Declared here as well
 * as in {@code V39__domain_event_triggers.sql} (and its workflow index in
 * {@code V42__inbound_event_receipt_workflow_index.sql}), for the schemas {@code ddl-auto} creates.
 */
@Entity
@Table(name = "inbound_event_receipt",
        indexes = {
                @Index(name = "idx_inbound_event_receipt_received_at", columnList = "received_at"),
                @Index(name = "idx_inbound_event_receipt_workflow_received", columnList = "workflow_id, received_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InboundEventReceiptEntity {

    @Id
    @Column(name = "receipt_key", length = 64)
    private String receiptKey;

    @Column(name = "receiver", length = 1024, nullable = false)
    private String receiver;

    @Column(name = "receiver_kind", length = 16, nullable = false)
    private String receiverKind;

    @Column(name = "destination", length = 255)
    private String destination;

    @Column(name = "event_type", length = 512)
    private String eventType;

    @Column(name = "event_source", length = 1024)
    private String eventSource;

    @Column(name = "event_id", length = 512)
    private String eventId;

    @Column(name = "outcome", length = 16, nullable = false)
    private String outcome;

    @Column(name = "workflow_id", length = 255)
    private String workflowId;

    @Column(name = "trigger_id", length = 160)
    private String triggerId;

    @Column(name = "business_key", length = 1024)
    private String businessKey;

    @Column(name = "message_name", length = 255)
    private String messageName;

    @Column(name = "correlation_key", length = 1024)
    private String correlationKey;

    @Column(name = "detail", length = 2000)
    private String detail;

    /** In UTC, as a plain TIMESTAMP like the engine's other columns. */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}
