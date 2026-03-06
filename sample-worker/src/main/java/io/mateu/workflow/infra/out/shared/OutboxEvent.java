package io.mateu.workflow.infra.out.shared;

public record OutboxEvent(
        String type,
        String id,
        Operation operation,
        String payload
) {
}
