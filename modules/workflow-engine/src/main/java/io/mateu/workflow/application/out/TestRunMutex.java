package io.mateu.workflow.application.out;

/** Excludes competing HTTP submissions before a process row exists, including across pods. */
@FunctionalInterface
public interface TestRunMutex {
    void run(String runId, Runnable action);
}
