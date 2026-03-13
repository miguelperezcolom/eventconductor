package io.mateu.workflow.application.usecases.stepexecution.checktimeout;

import java.util.Map;

public record CheckTimeoutCommand(
        String stepExecutionId
) {
}
