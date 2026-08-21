package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
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
public class ResourceEntity {
    @Id
    private String id;

    private LocalDateTime timestamp;

    private String processId;

    private String stepExecutionId;

    private String type;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String url;

}
