package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
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
    String values;
    String status;
    String userId;
    String userGroup;
}
