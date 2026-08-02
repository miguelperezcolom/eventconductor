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
        @JsonSubTypes.Type(value = TaskCancellationRequested.class, name = "task-cancellation-requested"),
        @JsonSubTypes.Type(value = TaskLogEmitted.class, name = "task-log-emitted"),
        @JsonSubTypes.Type(value = TaskResourceCreated.class, name = "task-resource-created"),
        @JsonSubTypes.Type(value = TaskStatusChanged.class, name = "task-status-changed"),
        @JsonSubTypes.Type(value = MessageReceived.class, name = "message-received"),
        @JsonSubTypes.Type(value = TimeoutCheckRequested.class, name = "timeout-check-requested"),
        @JsonSubTypes.Type(value = TimerCheckRequested.class, name = "timer-check-requested"),
        @JsonSubTypes.Type(value = RulePublished.class, name = "rule-published"),
        @JsonSubTypes.Type(value = RuleDeleted.class, name = "rule-deleted"),
})
public interface DomainEvent {

    /**
     * The process this event belongs to, or null when it belongs to none.
     *
     * <p>Used as the Kafka message key, which is what makes ownership structural: every event of
     * a process hashes to the same partition, a consumer group gives that partition to exactly
     * one pod, and so exactly one pod is ever working a given process. That is where per-process
     * serialization comes from — and, just as importantly, per-process <em>ordering</em>, which
     * an unkeyed topic does not give you at all.
     *
     * <p>Null for events that are not process-scoped (a rule publication) or that write only
     * their own independent row (a log line, a resource): those can be handled by any pod, so
     * pinning them to a partition would only cost balance.
     */
    default String partitionKey() {
        return null;
    }
}
