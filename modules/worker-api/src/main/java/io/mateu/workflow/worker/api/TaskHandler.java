package io.mateu.workflow.worker.api;

/**
 * The one thing a worker developer writes: the business logic of a task, typed to the contract's
 * input and output records. The runtime binds the process variables into {@code input}, invokes
 * this, and turns {@code output} back into variables and a {@code COMPLETED} reply.
 *
 * <p>Throw a {@link TaskFailure} (or a generated subclass, one per declared error) for a business
 * failure — the step fails with that code. Any other exception is an unexpected failure — the step
 * fails with the exception's text and the engine retries it per the step's {@code retries}. Return
 * normally to complete.
 *
 * @param <I> the task's input record
 * @param <O> the task's output record
 */
@FunctionalInterface
public interface TaskHandler<I, O> {

    O handle(I input, TaskContext context) throws Exception;
}
