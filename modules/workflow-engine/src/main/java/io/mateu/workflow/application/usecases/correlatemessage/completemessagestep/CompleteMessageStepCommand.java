package io.mateu.workflow.application.usecases.correlatemessage.completemessagestep;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

public record CompleteMessageStepCommand(String stepExecutionId, String messageName, String correlationKey,
                                         List<Variable> variables) {
}
