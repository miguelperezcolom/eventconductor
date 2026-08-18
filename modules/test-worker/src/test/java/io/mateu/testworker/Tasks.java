package io.mateu.testworker;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

import java.util.List;

/** Task requests as the engine sends them, so the tests read as scenarios rather than as records. */
public final class Tasks {

    private Tasks() {
    }

    public static TaskExecutionRequested task(String taskId, Variable... variables) {
        return new TaskExecutionRequested(
                "exec-1", "process-1", "booking", "step-" + taskId, taskId, List.of(variables));
    }

    public static TaskExecutionRequested task(String executionId, String processId, String taskId,
                                              Variable... variables) {
        return new TaskExecutionRequested(
                executionId, processId, "booking", "step-" + taskId, taskId, List.of(variables));
    }

    public static Variable testConfig(String json) {
        return new Variable("TEST_CONFIG", json);
    }
}
