package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.MessageType;

import java.util.List;

public record RegisterLogMessageCommand(
        String stepId,
        MessageType messageType,
        String message
) {
}
