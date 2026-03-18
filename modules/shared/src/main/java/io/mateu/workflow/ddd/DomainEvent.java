package io.mateu.workflow.ddd;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ProcessCreated.class, name = "process-created"),
        @JsonSubTypes.Type(value = TaskExecutionRequested.class, name = "task-execution-requested"),
        @JsonSubTypes.Type(value = TaskStatusChanged.class, name = "task-status-changed"),
        @JsonSubTypes.Type(value = StepExecutionStatusChanged.class, name = "step-execution-status-changed"),
})
public interface DomainEvent {
}
