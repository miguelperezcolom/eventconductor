package io.mateu.workflow.ddd;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mateu.workflow.dtos.events.ProcessCreated;
import io.mateu.workflow.dtos.events.TaskExecutionRequested;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ProcessCreated.class, name = "process-created"),
        @JsonSubTypes.Type(value = TaskExecutionRequested.class, name = "task-execution-requested"),
})
public interface DomainEvent {
}
