package io.mateu.testworker.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stored override.
 *
 * <p>The two collections are JSON text rather than child tables. They are edited as a whole, read
 * as a whole and never queried, so a child table would buy nothing and cost a join and a cascade;
 * the rest of the row is columns precisely because those <em>are</em> queried and shown.
 */
@Entity
@Table(name = "task_override", indexes = {
        @Index(name = "idx_task_override_enabled", columnList = "enabled")
})
@Getter
@Setter
@NoArgsConstructor
public class TaskOverrideEntity {

    @Id
    private String id;

    private String name;

    @Column(name = "workflow_definition_id")
    private String workflowDefinitionId;

    @Column(name = "step_id")
    private String stepId;

    @Column(name = "task_id")
    private String taskId;

    private boolean enabled;

    @Column(name = "duration_ms")
    private Long durationMs;

    private String outcome;

    @Column(length = 2000)
    private String reason;

    @Column(name = "failures_before_success")
    private Integer failuresBeforeSuccess;

    @Column(name = "reply_times")
    private Integer replyTimes;

    @Column(name = "ignore_cancellation")
    private boolean ignoreCancellation;

    @Column(name = "variables_json", length = 8000)
    private String variablesJson;

    @Column(name = "logs_json", length = 8000)
    private String logsJson;
}
