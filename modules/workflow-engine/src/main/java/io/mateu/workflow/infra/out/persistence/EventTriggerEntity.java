package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One {@code events.yaml} entry — see {@code EventTriggerRepository}. Declared here as well as in
 * {@code V40__event_trigger_files.sql}, for the schemas {@code ddl-auto} creates.
 */
@Entity
// The index V40 creates, declared here too for the schemas ddl-auto creates (see StepExecutionEntity).
@Table(name = "event_trigger", indexes = @jakarta.persistence.Index(name = "idx_event_trigger_workflow_id", columnList = "workflow_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventTriggerEntity {

    /** Hash of source and index: the source can be a long path or url, too long for a key. */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "source_key", length = 1024, nullable = false)
    private String sourceKey;

    @Column(name = "entry_index", nullable = false)
    private int entryIndex;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    /** The trigger's declared id; null for an entry that declares none. */
    @Column(name = "trigger_id", length = 160)
    private String triggerId;

    @Column(name = "destination", nullable = false)
    private String destination;

    @Column(name = "event_type", length = 512, nullable = false)
    private String eventType;

    @Column(name = "filter_expression", columnDefinition = "TEXT")
    private String filterExpression;

    @Column(name = "business_key", columnDefinition = "TEXT")
    private String businessKey;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;
}
