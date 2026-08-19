package io.mateu.testworker.application;

/**
 * The process asked for a scenario the worker cannot read.
 *
 * <p>Deliberately fatal to the task rather than something to log and shrug at. A malformed
 * {@code TEST_CONFIG} is a test that is not testing what it thinks it is, and the least useful
 * thing this worker could do is fall back to "take two seconds and succeed" — the run would go
 * green and the scenario would never have run. Failing the task puts the parse error in the
 * process log, where whoever wrote the JSON is already looking.
 */
public class ScenarioNotReadableException extends RuntimeException {

    public ScenarioNotReadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
