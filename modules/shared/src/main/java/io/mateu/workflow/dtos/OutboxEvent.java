package io.mateu.workflow.dtos;

public record OutboxEvent(
        String type,
        String id,
        Operation operation,
        String payload
) {
}
