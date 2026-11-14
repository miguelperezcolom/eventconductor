package io.mateu.workflow.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifyMojoTest {

    private final Path resources = new File("src/test/resources").getAbsoluteFile().toPath();
    private final Path migration = resources.resolve("migration-check");

    private VerifyMojo mojo(Path definitions, Path coverage) {
        var mojo = new VerifyMojo();
        mojo.workflowsDirectory = definitions.resolve("workflows").toFile();
        mojo.formsDirectory = definitions.resolve("forms").toFile();
        mojo.rulesDirectory = definitions.resolve("rules").toFile();
        mojo.tasksDirectory = definitions.resolve("tasks").toFile();
        mojo.migrationTo = "current";
        mojo.basedir = migration.toFile();
        mojo.coverageReport = coverage == null ? null : coverage.toFile();
        return mojo;
    }

    private static Path report(Path directory, double steps, double branches) throws Exception {
        var file = directory.resolve("coverage.json");
        Files.writeString(file, """
                {"tests": 3,
                 "totals": {"steps": {"covered": 9, "total": 10, "percent": %s},
                            "branches": {"covered": 1, "total": 2, "percent": %s},
                            "edges": {"covered": 8, "total": 9, "percent": 88.9}},
                 "definitions": [
                   {"id": "order", "uncoveredSteps": ["refund"],
                    "uncoveredBranches": [{"kind": "compensation", "from": "charge", "to": "refund"},
                                          {"kind": "sequence", "from": "a", "to": "b"}]},
                   {"id": "covered", "uncoveredSteps": [], "uncoveredBranches": []}]}
                """.formatted(steps, branches));
        return file;
    }

    @Test
    void validDefinitionsWithNothingElseConfiguredPass() {
        var mojo = mojo(resources.resolve("valid"), resources.resolve("no-report.json"));
        mojo.tasksDirectory = resources.resolve("valid/tasks").toFile();
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void everyCheckRunsAndTheBuildFailsOnceWithWhatEachFound(@TempDir Path directory) throws Exception {
        var mojo = mojo(resources.resolve("invalid"), report(directory, 90.0, 50.0));
        mojo.workflowsDirectory = migration.resolve("v2").toFile();
        mojo.formsDirectory = resources.resolve("invalid/forms").toFile();
        mojo.migrationFrom = "v1";
        mojo.minStepCoverage = 95.0;
        mojo.minBranchCoverage = 80.0;

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("[validate] EventConductor definition validation found")
                .hasMessageContaining("[check-migration] EventConductor migration check v1 → current")
                .hasMessageContaining("order: ERROR [charge]")
                .hasMessageContaining("[coverage] steps coverage is 90.0%, below the minimum of 95.0%;"
                        + " branches coverage is 50.0%, below the minimum of 80.0%")
                .hasMessageContaining("  order: step refund, compensation charge → refund")
                .hasMessageNotContaining("sequence a → b")
                .hasMessageNotContaining("  covered:");
    }

    @Test
    void aMigrationWithItsMappingAndCoverageAboveTheThresholdsPass(@TempDir Path directory) throws Exception {
        var mojo = mojo(resources.resolve("valid"), report(directory, 90.0, 50.0));
        mojo.workflowsDirectory = migration.resolve("v2").toFile();
        mojo.skipValidation = true;
        mojo.migrationFrom = "v1";
        mojo.migrationMapping = new File("mapping.yaml");
        mojo.minStepCoverage = 90.0;
        mojo.minBranchCoverage = 50.0;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void aThresholdWithoutAReportFails(@TempDir Path directory) {
        var mojo = mojo(resources.resolve("valid"), directory.resolve("missing.json"));
        mojo.skipValidation = true;
        mojo.minBranchCoverage = 10.0;

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("No workflow coverage report at");
    }

    @Test
    void anUnreadableReportIsAnError(@TempDir Path directory) throws Exception {
        var broken = Files.writeString(directory.resolve("coverage.json"), "{ not json");
        var mojo = mojo(resources.resolve("valid"), broken);
        mojo.skipValidation = true;

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Could not read the workflow coverage report");
    }

    @Test
    void skipSkipsEverything() {
        var mojo = mojo(resources.resolve("invalid"), null);
        mojo.skip = true;
        mojo.minStepCoverage = 100.0;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }
}
