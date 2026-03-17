package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.GeneratedValue;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.*;

import java.time.LocalDateTime;

@ReadOnly
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@With
public class Resource extends AggregateRoot implements Identifiable {
    @HiddenInCreate
    @GeneratedValue(UUIDValueGenerator.class)
    private String id;

    private LocalDateTime timestamp;

    private String processId;

    private String stepExecutionId;

    private String type;

    private String name;

    private String url;


    @Override
    public String toString() {
        return name;
    }

        @Override
        public String id() {
            return id;
        }

}
