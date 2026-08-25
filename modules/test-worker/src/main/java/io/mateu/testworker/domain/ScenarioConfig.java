package io.mateu.testworker.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * A whole test scenario: the reply for each task, and what everything else does.
 *
 * <p>This is what a process carries in its {@code TEST_CONFIG} variable:
 *
 * <pre>{@code
 * {
 *   "default": { "durationMs": 200, "outcome": "COMPLETED" },
 *   "tasks": {
 *     "reserve-seat": { "variables": [{"name": "seatId", "value": "12A"}] },
 *     "charge-card":  { "outcome": "ERROR", "reason": "card declined" },
 *     "notify":       { "outcome": "NO_REPLY" }
 *   }
 * }
 * }</pre>
 *
 * <p>A key in {@code tasks} matches the task's {@code taskId} first and its {@code stepId}
 * second. Both are offered because a workflow author thinks in steps and a worker is addressed by
 * task, and which of the two a reader has in front of them depends on where they are looking.
 *
 * @param defaults the reply for any task the map does not name, itself falling back to
 *                 {@link TaskScenario#baseline}.
 * @param tasks    replies by task id or step id.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ScenarioConfig(
        @JsonProperty("default") TaskScenario defaults,
        Map<String, TaskScenario> tasks) {

    /**
     * The scenario for one task, already merged: the named entry over {@code default} over
     * {@code baseline}.
     *
     * <p>Returns the merged default when no entry names this task, so a config that only sets
     * {@code default} still applies to everything — which is how "make every task take 50 ms" is
     * written.
     */
    public TaskScenario scenarioFor(String taskId, String stepId, TaskScenario baseline) {
        var fallback = defaults == null ? baseline : defaults.withFallback(baseline);
        var named = named(taskId);
        if (named == null) {
            named = named(stepId);
        }
        return named == null ? fallback : named.withFallback(fallback);
    }

    private TaskScenario named(String key) {
        return key == null || tasks == null ? null : tasks.get(key);
    }
}
