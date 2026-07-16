package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

public record CorrelateMessageCommand(String messageName, String correlationKey, List<Variable> variables) {
}
