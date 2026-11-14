package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Path coverage: which steps and routes of a definition the test's processes went through. */
@EventConductorTest(definitions = "src/test/coverage")
class WorkflowCoverageTest {

    private static WorkflowCoverage.DefinitionCoverage route(WorkflowCoverage coverage) {
        return coverage.report().definition("coverage-route");
    }

    @Test
    void oneHappyPathLeavesTheOtherBranchesUncovered(WorkflowTestRuntime runtime) {
        runtime.run(Scenario.load("src/test/scenarios/coverage-route.yaml").getFirst());

        var coverage = new WorkflowCoverage();
        runtime.recordInto(coverage);
        var route = route(coverage);

        assertThat(route.name()).isEqualTo("Every kind of route");
        assertThat(route.uncoveredSteps()).containsExactlyInAnyOrder("standard", "escalate", "escalated", "release");
        assertThat(route.uncoveredBranches()).extracting(Object::toString).containsExactlyInAnyOrder(
                "choice route → standard", "sequence standard → end", "timeout check → escalate",
                "sequence escalate → escalated", "compensation reserve → release", "loopBack standard → check");
        // Of the five routes that are not plain links, only the CHOICE's express branch was taken.
        assertThat(route.branches()).isEqualTo(new WorkflowCoverage.Count(1, 5));
        assertThat(route.steps().covered()).isEqualTo(6);
        assertThat(route.steps().percent()).isEqualTo(60.0);
        assertThat(coverage.report().definition("no-such-definition")).isNull();
        assertThat(coverage.report().tests()).isEqualTo(1);
    }

    @Test
    void everyScenarioTogetherCoversEveryRoute(WorkflowTestRuntime runtime) {
        for (var scenario : Scenario.load("src/test/scenarios")) {
            runtime.run(scenario);
        }

        var coverage = new WorkflowCoverage();
        runtime.recordInto(coverage);
        var route = route(coverage);

        assertThat(route.uncoveredSteps()).isEmpty();
        assertThat(route.uncoveredBranches()).isEmpty();
        assertThat(route.branches()).isEqualTo(new WorkflowCoverage.Count(5, 5));
        assertThat(route.edges().percent()).isEqualTo(100.0);
        // Definitions no process of this test ran are in the report too, at 0%.
        assertThat(coverage.report().definitions()).extracting(WorkflowCoverage.DefinitionCoverage::id)
                .contains("approval", "flaky-charge");
        assertThat(coverage.report().definition("flaky-charge").steps().covered()).isZero();
        // Inline PROCESS bodies are definitions of their own.
        assertThat(coverage.report().definitions()).extracting(WorkflowCoverage.DefinitionCoverage::id)
                .anyMatch(id -> id.contains("."));
    }

    @Test
    void theReportIsWrittenAsJsonAndText(WorkflowTestRuntime runtime, @TempDir Path directory) throws Exception {
        runtime.run(Scenario.load("src/test/scenarios/coverage-route.yaml").getLast());
        var coverage = new WorkflowCoverage();
        runtime.recordInto(coverage);

        coverage.write(directory);

        var json = Values.JSON.readTree(Files.readString(directory.resolve(WorkflowCoverage.JSON_FILE)));
        assertThat(json.path("tests").asInt()).isEqualTo(1);
        assertThat(json.path("totals").path("steps").path("total").asInt()).isPositive();
        var route = java.util.stream.StreamSupport.stream(json.path("definitions").spliterator(), false)
                .filter(d -> "coverage-route".equals(d.path("id").asText())).findFirst().orElseThrow();
        assertThat(route.path("branches").path("covered").asInt()).isEqualTo(2);   // the standard branch, the compensation
        assertThat(route.path("uncoveredSteps").toString()).contains("express", "escalate");
        assertThat(route.path("uncoveredBranches").toString()).contains("\"kind\":\"timeout\"");

        var text = Files.readString(directory.resolve(WorkflowCoverage.TEXT_FILE));
        assertThat(text).contains("EventConductor workflow coverage — 1 test(s)", "TOTAL", "Not covered:",
                "  coverage-route", "    step express", "    timeout check → escalate");
    }

    @Test
    void theRunsReportFollowsTheSystemProperties(WorkflowTestRuntime runtime, @TempDir Path directory) {
        var previousDirectory = System.getProperty("eventconductor.coverage.directory");
        try {
            System.setProperty("eventconductor.coverage.directory", directory.toString());
            assertThat(WorkflowCoverage.directory()).isEqualTo(directory);
            runtime.recordCoverage();
            assertThat(directory.resolve(WorkflowCoverage.JSON_FILE)).exists();
            assertThat(WorkflowCoverage.current().report().definition("coverage-route")).isNotNull();

            System.setProperty("eventconductor.coverage.enabled", "false");
            assertThat(WorkflowCoverage.enabled()).isFalse();
            var other = directory.resolve("other");
            System.setProperty("eventconductor.coverage.directory", other.toString());
            runtime.recordCoverage();
            assertThat(other).doesNotExist();
        } finally {
            System.clearProperty("eventconductor.coverage.enabled");
            if (previousDirectory == null) {
                System.clearProperty("eventconductor.coverage.directory");
            } else {
                System.setProperty("eventconductor.coverage.directory", previousDirectory);
            }
        }
        assertThat(WorkflowCoverage.directory()).isEqualTo(Path.of(WorkflowCoverage.DEFAULT_DIRECTORY));
    }

    @Test
    void countsAndBranchesReadWell() {
        assertThat(new WorkflowCoverage.Count(0, 0).percent()).isEqualTo(100.0);
        assertThat(new WorkflowCoverage.Count(1, 3)).hasToString("1/3 (33.3%)");
        assertThat(new WorkflowCoverage.Branch("choice", "a", "b")).hasToString("choice a → b");
    }
}
