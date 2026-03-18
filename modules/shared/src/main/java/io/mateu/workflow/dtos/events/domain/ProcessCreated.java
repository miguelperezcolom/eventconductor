package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

public record ProcessCreated(String processId, List<Variable> variables) implements DomainEvent {
}
