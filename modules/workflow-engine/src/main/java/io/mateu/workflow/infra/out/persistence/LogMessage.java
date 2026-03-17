package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter@Setter
@NoArgsConstructor@AllArgsConstructor
public class LogMessage {
    @Id
    private String id;

    private LocalDateTime timestamp;

    private String processId;

    private String stepExecutionId;

    private String messageType;

    private String message;

    private String workerId;

}
