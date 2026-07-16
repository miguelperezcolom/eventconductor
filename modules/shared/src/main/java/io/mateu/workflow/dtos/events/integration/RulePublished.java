package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

public record RulePublished(String ruleId, String name, int version, String ruleJson) implements DomainEvent {
}
