package io.mateu.workflow.dtos.events.domain;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

public record ProcessCreationRequested(String processId, List<Variable> variables) implements DomainEvent {
}
