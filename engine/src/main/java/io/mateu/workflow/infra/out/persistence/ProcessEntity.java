package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter@Setter
public class ProcessEntity {
    @Id
    private String id;

    private String businessKey;

    private String variables;

    private String status;

    private int completionPercentage;

    private String log;
}
