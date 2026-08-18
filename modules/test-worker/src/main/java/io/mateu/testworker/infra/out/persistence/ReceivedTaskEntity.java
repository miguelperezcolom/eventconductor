package io.mateu.testworker.infra.out.persistence;

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
 * A task this worker was handed.
 *
 * <p>Indexed by process and step because that pair is what {@code failuresBeforeSuccess} counts,
 * and it is counted on every single task the worker receives — an unindexed scan there would make
 * the simulator slower the longer a test ran, which is a strange failure to debug.
 */
@Entity
@Table(name = "received_task", indexes = {
        @Index(name = "idx_received_task_process_step", columnList = "process_id, step_id"),
        @Index(name = "idx_received_task_received_at", columnList = "received_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ReceivedTaskEntity {

    /** The task execution id, which is the engine's step execution id under another name. */
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

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    private Integer attempt;

    private String source;

    @Column(name = "matched_by")
    private String matchedBy;

    private String outcome;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "replied_at")
    private LocalDateTime repliedAt;

    @Column(length = 2000)
    private String note;

    @Column(name = "request_variables_json", length = 8000)
    private String requestVariablesJson;

    @Column(name = "scenario_json", length = 8000)
    private String scenarioJson;
}
