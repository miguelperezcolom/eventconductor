package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.MessageType;

public record TaskLogEmitted(String taskExecutionId, MessageType messageType, String message) implements DomainEvent {
}
