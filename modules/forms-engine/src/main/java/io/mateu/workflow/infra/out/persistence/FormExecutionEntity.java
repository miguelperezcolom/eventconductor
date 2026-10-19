package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(indexes = {
        @Index(name = "idx_form_exec_status_user", columnList = "status, userId"),
        // V8: the inbox's sort and filter columns, and the escalation/cancellation lookup.
        @Index(name = "idx_form_exec_status_priority", columnList = "status, priority"),
        @Index(name = "idx_form_exec_status_due", columnList = "status, dueDate"),
        @Index(name = "idx_form_exec_business_key", columnList = "businessKey"),
        @Index(name = "idx_form_exec_step_execution", columnList = "stepExecutionId")
})
@Getter@Setter
@NoArgsConstructor
public class FormExecutionEntity {
    @Id
    private String id;
    String formId;
    String processId;
    String stepId;
    String stepExecutionId;
    @Column(columnDefinition = "TEXT")
    String variables;
    // "values" is a reserved SQL word; backticks make Hibernate emit dialect-correct quoting.
    @Column(name = "`values`", columnDefinition = "TEXT")
    String values;
    String status;
    String userId;
    String userGroup;

    /*
     * Assignment (V8). The assignee is userId. Candidates live in two tables of their own, one row
     * per name, so "offered to me or to one of my groups" is an indexed lookup rather than a scan of
     * JSON. Fetched by subselect: one query per page for each, not one per task.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "form_execution_candidate_user",
            joinColumns = @JoinColumn(name = "form_execution_id"),
            indexes = @Index(name = "idx_form_exec_candidate_user", columnList = "candidate"))
    @Column(name = "candidate", nullable = false)
    Set<String> candidateUsers = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "form_execution_candidate_group",
            joinColumns = @JoinColumn(name = "form_execution_id"),
            indexes = @Index(name = "idx_form_exec_candidate_group", columnList = "candidate"))
    @Column(name = "candidate", nullable = false)
    Set<String> candidateGroups = new LinkedHashSet<>();

    /** Null on rows from before V8, read as the default (50). */
    Integer priority;
    /** UTC. */
    LocalDateTime dueDate;
    /** UTC. */
    LocalDateTime followUpDate;
    String businessKey;
    /** UTC; null on rows from before V8. */
    LocalDateTime createdAt;
    Integer escalationLevel;
    /** UTC. */
    LocalDateTime escalatedAt;
    /** Null on rows from before V8: not restricted. */
    Boolean restricted;

    /** The shape this entity had before assignment, for the callers that predate it. */
    public FormExecutionEntity(String id, String formId, String processId, String stepId, String stepExecutionId,
                               String variables, String values, String status, String userId, String userGroup) {
        this.id = id;
        this.formId = formId;
        this.processId = processId;
        this.stepId = stepId;
        this.stepExecutionId = stepExecutionId;
        this.variables = variables;
        this.values = values;
        this.status = status;
        this.userId = userId;
        this.userGroup = userGroup;
    }
}
