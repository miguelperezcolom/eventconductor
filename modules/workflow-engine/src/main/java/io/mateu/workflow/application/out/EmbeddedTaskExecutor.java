package io.mateu.workflow.application.out;

import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

/**
 * Port to be implemented by the application when using embedded mode (workflow.mode=embedded).
 * Called whenever the engine needs a worker to execute a task step.
 * The implementation is responsible for actually running the task and calling back
 * the engine via UpdateStepExecutionUseCase when done.
 */
public interface EmbeddedTaskExecutor {
    void execute(TaskExecutionRequested request);
}
