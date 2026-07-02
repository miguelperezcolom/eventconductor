package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class FormExecutionEntity {
    @Id
    private String id;
    String formId;
    String processId;
    String stepId;
    String stepExecutionId;
    String variables;
    // "values" is a reserved SQL word; backticks make Hibernate emit dialect-correct quoting.
    @Column(name = "`values`")
    String values;
    String status;
    String userId;
    String userGroup;
}
