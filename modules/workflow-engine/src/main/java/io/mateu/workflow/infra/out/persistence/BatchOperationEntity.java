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
 * A batch operation (V49). The filter, the parameters, the skip reasons, the error sample and the caller
 * are JSON: they are read whole with the row, never queried by. {@code cursor_id} is the last process id
 * the runner reached — what makes a batch resumable after a restart.
 */
@Entity
@Table(name = "batch_operation", indexes = {
        @Index(name = "idx_batch_operation_status_created", columnList = "status, created_at"),
        @Index(name = "idx_batch_operation_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "op_type", length = 32, nullable = false)
    private String opType;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "filter_json", columnDefinition = "TEXT")
    private String filterJson;

    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

    private long matched;
    private long succeeded;
    private long failed;
    private long skipped;

    @Column(name = "skipped_by_reason_json", columnDefinition = "TEXT")
    private String skippedByReasonJson;

    @Column(name = "cursor_id", length = 255)
    private String cursorId;

    @Column(name = "errors_json", columnDefinition = "TEXT")
    private String errorsJson;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "caller_json", columnDefinition = "TEXT")
    private String callerJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(length = 1000)
    private String failure;
}
