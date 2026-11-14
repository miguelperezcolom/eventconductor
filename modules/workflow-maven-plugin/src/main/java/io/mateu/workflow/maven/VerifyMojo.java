package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything EventConductor checks in CI, as one goal: {@code validate} on every definition, then
 * {@code check-migration} against the version processes run now (when {@code migrationFrom} says which),
 * then the workflow coverage the test kit recorded against its thresholds (when any is set). Every check
 * runs, and the build fails once, listing what each found.
 *
 * <pre>
 *   mvn verify -Deventconductor.migration.from=origin/main                 # bound to the verify phase
 *   mvn eventconductor:verify -Deventconductor.migration.from=v3 -Deventconductor.coverage.minBranches=80
 * </pre>
 *
 * <p>Bound in the {@code pom.xml}, it runs at {@code verify}, after the tests — which is when the coverage
 * report ({@code target/eventconductor-coverage/coverage.json}, written by {@code workflow-test}) exists.
 * A threshold with no report fails: the tests did not run, or ran without the kit.
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class VerifyMojo extends AbstractMojo {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------------------------------------------------------------- validate

    /** Directory holding workflow definitions. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/workflows")
    File workflowsDirectory;

    /** Directory holding form definitions. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/forms")
    File formsDirectory;

    /** Directory holding rule definitions. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/rules")
    File rulesDirectory;

    /** Directory holding task contracts ({@code .ectask}). */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/tasks")
    File tasksDirectory;

    /** Fail when a configured directory has no definitions at all. */
    @Parameter(property = "eventconductor.validate.failOnMissing", defaultValue = "false")
    boolean failOnMissing;

    /**
     * Leave {@code validate} out — for a build that already binds {@code validate} to an earlier phase, so the
     * definitions are not validated twice.
     */
    @Parameter(property = "eventconductor.verify.skipValidation", defaultValue = "false")
    boolean skipValidation;

    // ---------------------------------------------------------------- check-migration

    /**
     * The version processes run now — a git ref, a path, or {@code current}; see {@code check-migration}'s
     * {@code from}. Unset, the migration check is skipped.
     */
    @Parameter(property = "eventconductor.migration.from")
    String migrationFrom;

    /** The version to move them to: {@code current} (the default), a path, or a git ref. */
    @Parameter(property = "eventconductor.migration.to", defaultValue = "current")
    String migrationTo;

    /** A YAML or JSON file of explicit step mappings: {@code {<workflow id>: {<old step>: <new step>}}}. */
    @Parameter(property = "eventconductor.migration.mapping")
    File migrationMapping;

    /** What a relative {@code migrationFrom}, {@code migrationTo} or {@code migrationMapping} path is relative to. */
    @Parameter(defaultValue = "${project.basedir}")
    File basedir;

    // ---------------------------------------------------------------- coverage

    /** The coverage report the test kit writes. */
    @Parameter(property = "eventconductor.coverage.report",
            defaultValue = "${project.build.directory}/eventconductor-coverage/coverage.json")
    File coverageReport;

    /** The lowest share of steps (in percent, 0–100) the tests must run, across every definition. Unset: no floor. */
    @Parameter(property = "eventconductor.coverage.minSteps")
    Double minStepCoverage;

    /**
     * The lowest share of branches (in percent) the tests must take: CHOICE branches, guarded links, timeout
     * routes, compensations and loop-backs. Unset: no floor.
     */
    @Parameter(property = "eventconductor.coverage.minBranches")
    Double minBranchCoverage;

    /** Skip every check. */
    @Parameter(property = "eventconductor.verify.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("EventConductor verify skipped (eventconductor.verify.skip=true).");
            return;
        }
        var failures = new ArrayList<String>();
        if (skipValidation) {
            getLog().info("EventConductor verify: validate skipped (eventconductor.verify.skipValidation=true).");
        } else {
            run("validate", validateMojo(), failures);
        }
        if (migrationFrom == null || migrationFrom.isBlank()) {
            getLog().info("EventConductor verify: check-migration skipped — say what processes run now with"
                    + " -Deventconductor.migration.from=<git ref | path>.");
        } else {
            run("check-migration", checkMigrationMojo(), failures);
        }
        checkCoverage(failures);
        if (!failures.isEmpty()) {
            throw new MojoFailureException("EventConductor verify failed:\n\n" + String.join("\n\n", failures));
        }
        getLog().info("EventConductor verify: every check passed.");
    }

    private void run(String check, AbstractMojo mojo, List<String> failures) throws MojoExecutionException {
        mojo.setLog(getLog());
        try {
            mojo.execute();
        } catch (MojoFailureException e) {
            failures.add("[" + check + "] " + e.getMessage());
        }
    }

    ValidateMojo validateMojo() {
        var mojo = new ValidateMojo();
        mojo.workflowsDirectory = workflowsDirectory;
        mojo.formsDirectory = formsDirectory;
        mojo.rulesDirectory = rulesDirectory;
        mojo.tasksDirectory = tasksDirectory;
        mojo.validateWorkflows = true;
        mojo.validateForms = true;
        mojo.validateRules = true;
        mojo.validateTasks = true;
        mojo.failOnError = true;
        mojo.failOnMissing = failOnMissing;
        mojo.skip = false;
        return mojo;
    }

    CheckMigrationMojo checkMigrationMojo() {
        var mojo = new CheckMigrationMojo();
        mojo.from = migrationFrom;
        mojo.to = migrationTo;
        mojo.mapping = migrationMapping;
        mojo.workflowsDirectory = workflowsDirectory;
        mojo.basedir = basedir;
        mojo.failOnError = true;
        mojo.skip = false;
        return mojo;
    }

    private void checkCoverage(List<String> failures) throws MojoExecutionException {
        var thresholds = minStepCoverage != null || minBranchCoverage != null;
        if (coverageReport == null || !coverageReport.isFile()) {
            if (thresholds) {
                failures.add("[coverage] No workflow coverage report at " + coverageReport + ": run the tests (with"
                        + " workflow-test) before this check, e.g. with `mvn verify`.");
            } else {
                getLog().debug("No workflow coverage report at " + coverageReport);
            }
            return;
        }
        JsonNode report;
        try {
            report = JSON.readTree(coverageReport);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not read the workflow coverage report " + coverageReport, e);
        }
        var totals = report.path("totals");
        var steps = totals.path("steps");
        var branches = totals.path("branches");
        getLog().info(String.format(Locale.ROOT, "EventConductor workflow coverage: steps %s, branches %s, edges %s"
                        + " (%d definition(s), %d test(s); details in %s)", describe(steps), describe(branches),
                describe(totals.path("edges")), report.path("definitions").size(), report.path("tests").asInt(),
                new File(coverageReport.getParentFile(), "coverage.txt")));
        var problems = new ArrayList<String>();
        below("steps", steps, minStepCoverage, problems);
        below("branches", branches, minBranchCoverage, problems);
        if (!problems.isEmpty()) {
            var gaps = new ArrayList<String>();
            for (var definition : report.path("definitions")) {
                var uncovered = new ArrayList<String>();
                definition.path("uncoveredSteps").forEach(step -> uncovered.add("step " + step.asText()));
                definition.path("uncoveredBranches").forEach(branch -> {
                    if (!"sequence".equals(branch.path("kind").asText())) {
                        uncovered.add(branch.path("kind").asText() + " " + branch.path("from").asText() + " → "
                                + branch.path("to").asText());
                    }
                });
                if (!uncovered.isEmpty()) {
                    gaps.add("  " + definition.path("id").asText() + ": " + String.join(", ", uncovered));
                }
            }
            failures.add("[coverage] " + String.join("; ", problems) + (gaps.isEmpty() ? "" : "\nNot covered:\n"
                    + String.join("\n", gaps)));
        }
    }

    private static void below(String what, JsonNode count, Double minimum, List<String> problems) {
        if (minimum == null) {
            return;
        }
        var percent = count.path("percent").asDouble(100.0);
        if (percent < minimum) {
            problems.add(String.format(Locale.ROOT, "%s coverage is %.1f%%, below the minimum of %.1f%%", what,
                    percent, minimum));
        }
    }

    private static String describe(JsonNode count) {
        return String.format(Locale.ROOT, "%d/%d (%.1f%%)", count.path("covered").asInt(), count.path("total").asInt(),
                count.path("percent").asDouble(100.0));
    }
}
