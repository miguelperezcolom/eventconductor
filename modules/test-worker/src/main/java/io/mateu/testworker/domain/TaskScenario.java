package io.mateu.testworker.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mateu.workflow.dtos.Variable;

import java.time.Duration;
import java.util.List;

/**
 * What the worker should do with one task: how long to take, what to say, what to hand back, and
 * how to finish.
 *
 * <p>Every component is boxed, and null means "not stated here" rather than "zero". That is what
 * makes {@link #withFallback} work: a scenario written for one task states only what differs, and
 * inherits the rest from the config's {@code default} block and, below that, from
 * {@link #baseline(Duration)}. A primitive {@code long durationMs} would make "I did not say"
 * indistinguishable from "finish instantly", and every scenario would have to restate everything.
 *
 * @param durationMs           how long the task takes before it replies.
 * @param outcome              how it ends. See {@link Outcome}.
 * @param reason               why it failed; sent as an {@code Error} log line before the failure,
 *                             so the process log says what happened. Ignored unless the outcome is
 *                             {@link Outcome#ERROR}.
 * @param logs                 lines emitted while the task runs.
 * @param variables            variables reported back with the reply, which the engine merges into
 *                             the process.
 * @param failuresBeforeSuccess fail the first N executions of this step within a process and
 *                             succeed afterwards — the shape of a retry policy under test. Counted
 *                             per process and step, because that is what the engine retries.
 * @param replyTimes           how many times to send the final reply. More than one is a
 *                             deliberately misbehaving worker, and what the engine does with the
 *                             duplicate is the thing being tested.
 * @param ignoreCancellation   keep working and reply anyway after the engine has cancelled the
 *                             task. Also a misbehaving worker, and also worth being able to point
 *                             at a running system.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TaskScenario(
        Long durationMs,
        Outcome outcome,
        String reason,
        List<LogLine> logs,
        List<Variable> variables,
        Integer failuresBeforeSuccess,
        Integer replyTimes,
        Boolean ignoreCancellation) {

    /**
     * What a task does when nothing has been said about it at all: takes the configured duration
     * and completes, changing nothing.
     *
     * <p>Unknown JSON properties are rejected rather than ignored ({@code ignoreUnknown = false}),
     * which is the opposite of the usual advice and right here: a scenario is a test's assertion
     * about what should happen, and a misspelled {@code durationMS} that silently means "take two
     * seconds" turns a test that proves nothing into a test that looks like it passed.
     */
    public static TaskScenario baseline(Duration defaultDuration) {
        return new TaskScenario(
                defaultDuration.toMillis(), Outcome.COMPLETED, null,
                List.of(), List.of(), 0, 1, false);
    }

    /** This scenario, with anything it leaves unsaid taken from {@code fallback}. */
    public TaskScenario withFallback(TaskScenario fallback) {
        if (fallback == null) {
            return this;
        }
        return new TaskScenario(
                durationMs != null ? durationMs : fallback.durationMs(),
                outcome != null ? outcome : fallback.outcome(),
                reason != null ? reason : fallback.reason(),
                logs != null ? logs : fallback.logs(),
                variables != null ? variables : fallback.variables(),
                failuresBeforeSuccess != null ? failuresBeforeSuccess : fallback.failuresBeforeSuccess(),
                replyTimes != null ? replyTimes : fallback.replyTimes(),
                ignoreCancellation != null ? ignoreCancellation : fallback.ignoreCancellation());
    }

    public Duration duration() {
        return Duration.ofMillis(durationMs == null || durationMs < 0 ? 0 : durationMs);
    }

    public List<LogLine> logLines() {
        return logs == null ? List.of() : logs;
    }

    public List<Variable> reportedVariables() {
        return variables == null ? List.of() : variables;
    }

    public int failuresFirst() {
        return failuresBeforeSuccess == null || failuresBeforeSuccess < 0 ? 0 : failuresBeforeSuccess;
    }

    /** At least one: a scenario asking for zero replies is asking for {@link Outcome#NO_REPLY}. */
    public int replies() {
        return replyTimes == null || replyTimes < 1 ? 1 : replyTimes;
    }

    public boolean ignoresCancellation() {
        return Boolean.TRUE.equals(ignoreCancellation);
    }

    /** This scenario forced to fail, keeping everything else it says. Used by the retry counter. */
    public TaskScenario failingWith(String forcedReason) {
        return new TaskScenario(durationMs, Outcome.ERROR, forcedReason, logs, variables,
                failuresBeforeSuccess, replyTimes, ignoreCancellation);
    }
}
