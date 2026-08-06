package io.mateu.workflow.infra.out.persistence;

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
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class ProcessIndexEntity {

    @Id
    private String processId;
    private String businessKey;
    private String workflowDefinitionId;
    private int workflowDefinitionVersion;
    private String status;
    private int completionPercentage;
    private LocalDateTime created;
    private LocalDateTime started;
    private LocalDateTime finished;
    private LocalDateTime updatedAt;
    private String shardId;
}
