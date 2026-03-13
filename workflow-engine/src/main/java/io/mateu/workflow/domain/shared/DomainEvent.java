package io.mateu.workflow.domain.shared;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mateu.dtos.FormDto;
import io.mateu.workflow.domain.events.ProcessCreated;
import io.mateu.workflow.domain.events.StepExecutionRequested;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ProcessCreated.class, name = "process-created"),
        @JsonSubTypes.Type(value = StepExecutionRequested.class, name = "step-execution-requested"),
})
public interface DomainEvent {
}
