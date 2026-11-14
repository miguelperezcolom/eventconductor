package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.Precondition;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Path coverage of the workflow definitions across a test run: which steps the tests' processes reached,
 * and which of the routes between them — CHOICE branches, guarded links, timeout routes, compensations,
 * loop-backs and plain links — they took.
 *
 * <p>The kit records it by itself. After every {@link EventConductorTest} test it adds what that test's
 * processes did, and writes the report of the run so far to {@code target/eventconductor-coverage/}:
 * {@code coverage.json} (what {@code eventconductor:verify} reads to enforce a threshold) and
 * {@code coverage.txt} (a table per definition, then everything not covered). Every definition the engine
 * knows is in it, so a definition no test starts shows as 0%.
 *
 * <p>What counts:
 * <ul>
 *   <li><b>a step</b> is covered when a process ran it — started it, whatever it ended in;</li>
 *   <li><b>a link</b> {@code A → B} when, in one process, {@code A} completed and {@code B} ran —
 *       {@code choice} when {@code A} is a CHOICE, {@code guard} when the link has a condition,
 *       {@code sequence} otherwise;</li>
 *   <li><b>a timeout route</b> {@code A → T} ({@code onTimeoutStepId}) when {@code T} ran;</li>
 *   <li><b>a compensation</b> {@code A → C} when {@code A} completed and {@code C} ran;</li>
 *   <li><b>a loop-back</b> {@code A → T} when {@code A} completed and {@code T} ran more than once.</li>
 * </ul>
 * <b>Branches</b> are every route but the plain links (those follow from the steps), <b>edges</b> all of them.
 *
 * <p>System properties: {@code eventconductor.coverage.enabled=false} turns it off,
 * {@code eventconductor.coverage.directory} writes elsewhere. The report covers one JVM: with several
 * Surefire forks, each overwrites it.
 */
public final class WorkflowCoverage {

    /** Where the report goes, relative to the working directory, unless the system property says otherwise. */
    public static final String DEFAULT_DIRECTORY = "target/eventconductor-coverage";
    public static final String JSON_FILE = "coverage.json";
    public static final String TEXT_FILE = "coverage.txt";

    private static final WorkflowCoverage RUN = new WorkflowCoverage();

    /** One route between two steps of a definition. */
    public record Branch(String kind, String from, String to) {
        String key() {
            return kind + '|' + from + '|' + to;
        }

        @Override
        public String toString() {
            return kind + " " + from + " → " + to;
        }
    }

    /** Covered of total, and the percentage (100 when there is nothing to cover). */
    public record Count(int covered, int total) {
        public double percent() {
            return total == 0 ? 100.0 : Math.round(covered * 1000.0 / total) / 10.0;
        }

        Count plus(Count other) {
            return new Count(covered + other.covered, total + other.total);
        }

        @Override
        public String toString() {
            return covered + "/" + total + " (" + percent() + "%)";
        }
    }

    /** The coverage of one definition. */
    public record DefinitionCoverage(String id, String name, int version, Count steps, Count branches, Count edges,
                                     List<String> uncoveredSteps, List<Branch> uncoveredBranches) {
    }

    /** The coverage of the run so far. */
    public record Report(Instant generatedAt, int tests, Count steps, Count branches, Count edges,
                         List<DefinitionCoverage> definitions) {

        /** One definition's coverage, or null when the engine does not know it. */
        public DefinitionCoverage definition(String id) {
            return definitions.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
        }
    }

    private record Model(String id, String name, int version, List<String> steps, List<Branch> branches) {
    }

    private final Map<String, Model> models = new TreeMap<>();
    private final Map<String, Set<String>> coveredSteps = new LinkedHashMap<>();
    private final Map<String, Set<String>> coveredBranches = new LinkedHashMap<>();
    private int tests;

    WorkflowCoverage() {
    }

    /** The coverage this JVM's tests have recorded so far. */
    public static WorkflowCoverage current() {
        return RUN;
    }

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("eventconductor.coverage.enabled", "true").strip());
    }

    static Path directory() {
        var configured = System.getProperty("eventconductor.coverage.directory");
        return Path.of(configured == null || configured.isBlank() ? DEFAULT_DIRECTORY : configured.strip());
    }

    // ---------------------------------------------------------------- recording

    /** Makes {@code definitions} part of the report (the highest version of each id is the one measured). */
    synchronized void register(Collection<WorkflowDefinition> definitions) {
        for (var definition : definitions) {
            if (definition == null || definition.id() == null || definition.steps() == null) {
                continue;
            }
            var known = models.get(definition.id());
            if (known == null || known.version() <= definition.version()) {
                models.put(definition.id(), model(definition));
            }
        }
    }

    /** Adds what one process of {@code definitionId} did. */
    synchronized void record(String definitionId, List<StepExecution> executions) {
        if (definitionId == null) {
            return;
        }
        var ran = new LinkedHashMap<String, Integer>();
        var completed = new LinkedHashSet<String>();
        for (var execution : executions) {
            // The engine lays out an execution for every step up front: one that never started (a branch
            // not taken, a compensation not needed) is cancelled or still waiting, and was not run.
            if (execution.getStepId() == null || (execution.getStartedAt() == null
                    && execution.getStatus() != StepExecutionStatus.COMPLETED)) {
                continue;
            }
            ran.merge(execution.getStepId(), 1, Integer::sum);
            if (execution.getStatus() == StepExecutionStatus.COMPLETED) {
                completed.add(execution.getStepId());
            }
        }
        coveredSteps.computeIfAbsent(definitionId, id -> new LinkedHashSet<>()).addAll(ran.keySet());
        var model = models.get(definitionId);
        if (model == null) {
            return;
        }
        var branches = coveredBranches.computeIfAbsent(definitionId, id -> new LinkedHashSet<>());
        for (var branch : model.branches()) {
            var taken = switch (branch.kind()) {
                case "timeout" -> ran.containsKey(branch.from()) && ran.containsKey(branch.to());
                case "loopBack" -> completed.contains(branch.from()) && ran.getOrDefault(branch.to(), 0) > 1;
                default -> completed.contains(branch.from()) && ran.containsKey(branch.to());
            };
            if (taken) {
                branches.add(branch.key());
            }
        }
    }

    synchronized void testFinished() {
        tests++;
    }

    private static Model model(WorkflowDefinition definition) {
        var steps = new ArrayList<String>();
        var types = new LinkedHashMap<String, StepType>();
        for (var step : definition.steps()) {
            if (step != null && step.id() != null) {
                steps.add(step.id());
                types.put(step.id(), step.type());
            }
        }
        var branches = new LinkedHashSet<Branch>();
        for (var step : definition.steps()) {
            if (step == null || step.id() == null) {
                continue;
            }
            for (Precondition link : links(step)) {
                if (link.stepId() == null || !types.containsKey(link.stepId())) {
                    continue;
                }
                var kind = types.get(link.stepId()) == StepType.CHOICE ? "choice"
                        : link.hasGuard() ? "guard" : "sequence";
                branches.add(new Branch(kind, link.stepId(), step.id()));
            }
            if (present(step.onTimeoutStepId())) {
                branches.add(new Branch("timeout", step.id(), step.onTimeoutStepId()));
            }
            if (step.compensable() && present(step.compensationStepId())) {
                branches.add(new Branch("compensation", step.id(), step.compensationStepId()));
            }
            if (step.loopBack() != null && present(step.loopBack().to())) {
                branches.add(new Branch("loopBack", step.id(), step.loopBack().to()));
            }
        }
        var name = definition.name() == null ? definition.id() : definition.name();
        return new Model(definition.id(), name, definition.version(), List.copyOf(steps), List.copyOf(branches));
    }

    private static List<Precondition> links(Step step) {
        try {
            return step.resolvedPreconditions();
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }

    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }

    // ---------------------------------------------------------------- the report

    /** The report of what has been recorded so far. */
    public synchronized Report report() {
        var definitions = new ArrayList<DefinitionCoverage>();
        var steps = new Count(0, 0);
        var branches = new Count(0, 0);
        var edges = new Count(0, 0);
        for (var model : models.values()) {
            var ranSteps = coveredSteps.getOrDefault(model.id(), Set.of());
            var takenBranches = coveredBranches.getOrDefault(model.id(), Set.of());
            var uncoveredSteps = model.steps().stream().filter(step -> !ranSteps.contains(step)).toList();
            var nonSequence = model.branches().stream().filter(b -> !"sequence".equals(b.kind())).toList();
            var uncoveredBranches = model.branches().stream().filter(b -> !takenBranches.contains(b.key())).toList();
            var stepCount = new Count(model.steps().size() - uncoveredSteps.size(), model.steps().size());
            var branchCount = new Count((int) nonSequence.stream().filter(b -> takenBranches.contains(b.key())).count(),
                    nonSequence.size());
            var edgeCount = new Count(model.branches().size() - uncoveredBranches.size(), model.branches().size());
            definitions.add(new DefinitionCoverage(model.id(), model.name(), model.version(), stepCount, branchCount,
                    edgeCount, uncoveredSteps, uncoveredBranches));
            steps = steps.plus(stepCount);
            branches = branches.plus(branchCount);
            edges = edges.plus(edgeCount);
        }
        return new Report(Instant.now(), tests, steps, branches, edges,
                List.copyOf(definitions));
    }

    /** Writes the report to {@code directory}: {@value #JSON_FILE} and {@value #TEXT_FILE}. */
    public void write(Path directory) {
        var report = report();
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(JSON_FILE), json(report));
            Files.writeString(directory.resolve(TEXT_FILE), text(report));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the workflow coverage report to " + directory, e);
        }
    }

    static String json(Report report) {
        var root = new LinkedHashMap<String, Object>();
        root.put("generatedAt", report.generatedAt().toString());
        root.put("tests", report.tests());
        var totals = new LinkedHashMap<String, Object>();
        totals.put("steps", count(report.steps()));
        totals.put("branches", count(report.branches()));
        totals.put("edges", count(report.edges()));
        root.put("totals", totals);
        var definitions = new ArrayList<Map<String, Object>>();
        for (var definition : report.definitions()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", definition.id());
            entry.put("name", definition.name());
            entry.put("version", definition.version());
            entry.put("steps", count(definition.steps()));
            entry.put("branches", count(definition.branches()));
            entry.put("edges", count(definition.edges()));
            entry.put("uncoveredSteps", definition.uncoveredSteps());
            entry.put("uncoveredBranches", definition.uncoveredBranches().stream().map(b -> {
                var branch = new LinkedHashMap<String, Object>();
                branch.put("kind", b.kind());
                branch.put("from", b.from());
                branch.put("to", b.to());
                return branch;
            }).toList());
            definitions.add(entry);
        }
        root.put("definitions", definitions);
        try {
            return Values.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> count(Count count) {
        var map = new LinkedHashMap<String, Object>();
        map.put("covered", count.covered());
        map.put("total", count.total());
        map.put("percent", count.percent());
        return map;
    }

    static String text(Report report) {
        var width = Math.max(10, report.definitions().stream().mapToInt(d -> d.id().length()).max().orElse(0));
        var row = "%-" + width + "s  %-18s %-18s %-18s%n";
        var out = new StringBuilder();
        out.append(String.format("EventConductor workflow coverage — %d test(s), %d definition(s)%n%n",
                report.tests(), report.definitions().size()));
        out.append(String.format(row, "definition", "steps", "branches", "edges"));
        for (var definition : report.definitions()) {
            out.append(String.format(row, definition.id(), definition.steps(), definition.branches(), definition.edges()));
        }
        out.append(String.format(row, "TOTAL", report.steps(), report.branches(), report.edges()));
        var gaps = report.definitions().stream()
                .filter(d -> !d.uncoveredSteps().isEmpty() || !d.uncoveredBranches().isEmpty()).toList();
        if (!gaps.isEmpty()) {
            out.append(String.format("%nNot covered:%n"));
            for (var definition : gaps) {
                out.append(String.format("  %s%n", definition.id()));
                definition.uncoveredSteps().forEach(step -> out.append(String.format("    step %s%n", step)));
                definition.uncoveredBranches().forEach(branch -> out.append(String.format("    %s%n", branch)));
            }
        }
        return out.toString();
    }
}
