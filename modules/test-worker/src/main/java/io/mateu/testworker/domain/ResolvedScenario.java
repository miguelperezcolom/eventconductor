package io.mateu.testworker.domain;

/**
 * The scenario a task will actually be played with, and where it came from.
 *
 * <p>The source travels with the scenario because it is the first thing anyone asks when a test
 * behaves unexpectedly: whether the reply came from the {@code TEST_CONFIG} the test wrote, or
 * from an override someone left enabled in the UI three days ago.
 */
public record ResolvedScenario(TaskScenario scenario, ScenarioSource source, String matchedBy) {

    public static ResolvedScenario of(TaskScenario scenario, ScenarioSource source) {
        return new ResolvedScenario(scenario, source, null);
    }
}
