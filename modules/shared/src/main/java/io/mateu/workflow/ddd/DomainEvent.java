package io.mateu.workflow.ddd;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.*;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ProcessCreationRequested.class, name = "process-creation-requested"),
        @JsonSubTypes.Type(value = ProcessCreated.class, name = "process-created"),
        @JsonSubTypes.Type(value = ProcessCancellationRequested.class, name = "process-cancellation-requested"),
        @JsonSubTypes.Type(value = StepExecutionStatusChanged.class, name = "step-execution-status-changed"),
        @JsonSubTypes.Type(value = StepExecutionsCreationRequested.class, name = "step-execution-creation-requested"),
        @JsonSubTypes.Type(value = TaskExecutionRequested.class, name = "task-execution-requested"),
        @JsonSubTypes.Type(value = TaskLogEmitted.class, name = "task-log-emitted"),
        @JsonSubTypes.Type(value = TaskResourceCreated.class, name = "task-resource-created"),
        @JsonSubTypes.Type(value = TaskStatusChanged.class, name = "task-status-changed"),
})
public interface DomainEvent {
}
