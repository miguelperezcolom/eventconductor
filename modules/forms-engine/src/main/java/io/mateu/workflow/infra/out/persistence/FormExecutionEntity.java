package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Entity
@Table(indexes = @Index(name = "idx_form_exec_status_user", columnList = "status, userId"))
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
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
}
