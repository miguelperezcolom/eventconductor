package io.mateu.workflow.application.usecases.checktimeout.checksteptimeout;

public record CheckStepTimeoutCommand(
        String stepExecutionId
) {
}
