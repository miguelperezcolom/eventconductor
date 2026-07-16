package io.mateu.workflow.application.usecases.stepexecution.retry;

public record RetryStepExecutionCommand(
        String stepExecutionId
) {
}
