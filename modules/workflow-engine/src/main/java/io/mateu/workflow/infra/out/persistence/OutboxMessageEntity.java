package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.ddd.DomainEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.toJson;

@Entity
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

    public OutboxMessageEntity(DomainEvent event) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
        this.status = OutboxMessageStatus.Pending.name();
        this.messageType = event.getClass().getName();
        this.payload = toJson(event);
    }
}
