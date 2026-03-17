package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.toJson;

@ReadOnly
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@With
public class LogMessage extends AggregateRoot implements Identifiable {
    @HiddenInCreate
    @GeneratedValue(UUIDValueGenerator.class)
    private String id;

    private LocalDateTime timestamp;

    private String processId;

    private String stepExecutionId;

    private String messageType;

    private String message;

    private String workerId;


    @Override
    public String toString() {
        return message;
    }

        @Override
        public String id() {
            return id;
        }

}
