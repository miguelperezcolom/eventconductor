package io.mateu.workflow.test;

/** Computes a mocked task's outcome from what it was sent. */
@FunctionalInterface
public interface TaskAnswer {

    TaskOutcome answer(TaskInvocation invocation);
}
