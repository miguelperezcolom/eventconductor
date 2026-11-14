package io.mateu.workflow.test;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scenarios written as data, run by a parameterized test, a test factory, or one at a time. */
@EventConductorTest(definitions = "src/test/coverage")
class ScenariosTest {

    @ParameterizedTest(name = "{0}")
    @ScenarioSource("classpath:scenarios/")
    void everyKitFeatureAScenarioCanUse(Scenario scenario, WorkflowTestRuntime runtime) {
        runtime.run(scenario);
    }

    @TestFactory
    Stream<DynamicTest> theRouteScenarios(WorkflowTestRuntime runtime) {
        return runtime.scenarios("src/test/scenarios");
    }

    @Test
    void aScenarioReturnsItsProcessForMoreAssertions(WorkflowTestRuntime runtime) {
        var scenario = Scenario.load("classpath:scenarios/kit-features.yaml").getFirst();
        assertThat(scenario.name()).isEqualTo("an HTTP call retried after a 503");
        assertThat(scenario.source()).isEqualTo("classpath:scenarios/kit-features.yaml");
        assertThat(scenario.workflow()).isEqualTo("http-charge");
        assertThat(scenario).hasToString("an HTTP call retried after a 503 (classpath:scenarios/kit-features.yaml)");
        assertThat(scenario.spec().path("http").has("charge")).isTrue();

        var process = runtime.run(scenario);

        assertThat(process).isCompleted().hasVariable("chargeStatus", 201);
    }

    @Test
    void theFilesDefaultsAreMergedIntoEachScenario() {
        var scenarios = Scenario.load("src/test/scenarios/coverage-route.yaml");
        assertThat(scenarios).hasSize(4);
        var express = scenarios.getFirst().spec();
        assertThat(express.path("workflow").asText()).isEqualTo("coverage-route");
        assertThat(express.path("tasks").properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactly("reserve", "check", "release", "express");
        assertThat(scenarios.get(2).spec().path("tasks").path("check").path("timeout").asBoolean()).isTrue();
    }

    @Test
    void aListOrASingleScenarioAndJsonAreScenarioFilesToo(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("list.yaml"), """
                - { workflow: approval }
                - { workflow: approval, name: second }
                """);
        Files.writeString(directory.resolve("single.json"), """
                {"name": "alone", "workflow": "approval"}
                """);
        Files.writeString(directory.resolve("notes.txt"), "not a scenario");

        var scenarios = Scenario.load(directory.toString());

        assertThat(scenarios).extracting(Scenario::name).containsExactly("scenario 1", "second", "alone");
        assertThat(Scenario.parse("workflow: approval", "inline.yaml")).hasSize(1);
    }

    @Test
    void whatIsNotAScenarioFileIsRefused(@TempDir Path directory) {
        assertThatThrownBy(() -> Scenario.load(directory.resolve("missing").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No scenario file or directory");
        assertThatThrownBy(() -> Scenario.load(directory.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No scenarios found");
        assertThatThrownBy(() -> Scenario.load("classpath:scenarios/missing.yaml"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No scenario file at classpath:");
        assertThatThrownBy(() -> Scenario.parse("{ not: [yaml", "bad.yaml"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bad.yaml is not a scenario file");
        assertThatThrownBy(() -> Scenario.parse("42", "number.yaml"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holds no scenario");
        assertThatThrownBy(() -> Scenario.parse("scenarios: {a: 1}", "map.yaml"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'scenarios' must be a list");
        assertThatThrownBy(() -> Scenario.parse("scenarios: [1]", "items.yaml"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scenario 1 is not an object");
        assertThatThrownBy(() -> Scenario.parse("name: nameless", "noworkflow.yaml"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("names no workflow");
    }

    private static Scenario scenario(String yaml) {
        return Scenario.parse(yaml, "test.yaml").getFirst();
    }

    @Test
    void aMistakeInAScenarioSaysWhereItIs(WorkflowTestRuntime runtime) {
        var mistakes = java.util.Map.of(
                "{workflow: approval, expected: {}}", "unknown key 'expected'",
                "{workflow: approval, expect: {state: COMPLETED}}", "unknown expectation 'state'",
                "{workflow: approval, expect: {status: DONE}}", "unknown status 'DONE'",
                "{workflow: approval, then: [{wait: PT1H}]}", "unknown action 'wait'",
                "{workflow: approval, then: [{advanceTime: PT1H, cancel: true}]}", "must be an object with one key",
                "{workflow: approval, then: [{sendMessage: {}}]}", "'sendMessage' needs 'name'",
                "{workflow: approval, tasks: [a]}", "'tasks' must be a map",
                "{workflow: approval, variables: [a]}", "expected a map of variables",
                "{workflow: approval, steps: {approve: {done: true}}}", "says neither complete, fail nor timeout");
        mistakes.forEach((yaml, message) -> assertThatThrownBy(() -> runtime.run(scenario(yaml)))
                .as(yaml).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scenario 'scenario 1' (test.yaml)").hasMessageContaining(message));
        assertThatThrownBy(() -> runtime.run(scenario("{workflow: triage, llm: {triage: {text: hi}}}")))
                .hasMessageContaining("says neither answer nor fail");
        assertThatThrownBy(() -> runtime.run(scenario("{workflow: claim-assessment, agents: {assess: {says: hi}}}")))
                .hasMessageContaining("says neither output, fail, reviewRequired nor cancelled");
    }

    @Test
    void anExpectationThatDoesNotHoldFailsLikeAnAssertion(WorkflowTestRuntime runtime) {
        assertThatThrownBy(() -> runtime.run(scenario("""
                name: wrong status
                workflow: approval
                expect: { status: COMPLETED }
                """))).isInstanceOf(AssertionError.class).hasMessageContaining("wrong status").hasMessageContaining("RUNNING");
        assertThatThrownBy(() -> runtime.run(scenario("""
                name: wrong count
                workflow: approval
                steps: { approve: { complete: {} }, notify: { complete: {} } }
                expect: { invocations: { notify: 2 } }
                """))).isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected step 'notify' to have been dispatched 2 time(s) but it was 1 time(s)");
    }

    @Test
    void aConditionOrATemplateThatDoesNotEvaluateFailsTheStep(WorkflowTestRuntime runtime) {
        // The answer is computed when the engine dispatches the task: its mistake fails the step.
        var condition = runtime.run(scenario("""
                workflow: slow-supplier
                steps:
                  order:
                    - { when: "orderId ==", complete: {} }
                """));
        assertThat(condition).isFailed().hasStepStatus("order", io.mateu.workflow.domain.aggregates.StepExecutionStatus.ERROR);

        var template = runtime.run(scenario("""
                workflow: slow-supplier
                steps:
                  order: { complete: { orderId: "${orderId ==}" } }
                """));
        assertThat(template).isFailed();

        var noRule = runtime.run(scenario("""
                workflow: slow-supplier
                steps:
                  order: [ { when: "false", complete: {} } ]
                """));
        assertThat(noRule).isFailed();
    }
}
