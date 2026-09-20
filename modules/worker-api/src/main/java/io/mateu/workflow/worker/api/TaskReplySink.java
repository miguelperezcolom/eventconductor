package io.mateu.workflow.worker.api;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

import java.util.List;

/**
 * How the runtime answers the engine, abstracted away from the transport so the same
 * {@link TaskDispatcher} serves both modes. The Kafka adapter implements it over
 * {@code WorkerReply} (Spring Cloud Stream); the embedded adapter over
 * {@code UpdateStepExecutionUseCase} (a direct call). Keeping this interface here — and free of any
 * Spring Cloud Stream type — is what lets {@code worker-api} stay broker-free.
 *
 * <p>An implementation may throw {@link ReplyNotAcceptedException} to signal the reply was refused
 * and the task must be redelivered; every other reason to fail has already become a {@code failed}.
 */
public interface TaskReplySink {

    /** Report the task is running (resets the step's timeout clock). */
    void running(TaskExecutionRequested task);

    /** Report success with the output variables. */
    void completed(TaskExecutionRequested task, List<Variable> variables);

    /** Report failure with a reason. */
    void failed(TaskExecutionRequested task, List<Variable> variables, String reason);
}
