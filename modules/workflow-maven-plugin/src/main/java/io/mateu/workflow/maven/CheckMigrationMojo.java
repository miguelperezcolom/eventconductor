package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.analysis.MigrationRules;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Checks that the processes running an older version of each workflow could be moved onto the newer one —
 * the CI counterpart of the engine's process migration (Temporal's "replay the history against the new
 * worker", for a DSL-first engine: the check reads the two definitions, no history needed).
 *
 * <pre>
 *   mvn eventconductor:check-migration -Dfrom=v3                 # a git tag or ref, against the working copy
 *   mvn eventconductor:check-migration -Dfrom=v3 -Dto=v4
 *   mvn eventconductor:check-migration -Dfrom=released/workflows -Dmapping=src/main/resources/migration.yaml
 * </pre>
 *
 * <p>{@code from} and {@code to} are each {@code current} (the workflows directory as it is), a path (a
 * definition file or a directory of them) or a git ref, read with {@code git show <ref>:<file>} for every
 * definition file of the workflows directory. For every workflow in both, each step of the old version must
 * have a compatible counterpart in the new one — by id, or by the {@code mapping} file
 * ({@code {<workflow id>: {<old step>: <new step>}}}, the same mapping a migration is given) — when a process
 * can be waiting in it; see {@link MigrationRules#checkDefinitions}. Errors fail the build (unless
 * {@code failOnError} is false); warnings are reported.
 */
@Mojo(name = "check-migration", threadSafe = true, requiresProject = false)
public class CheckMigrationMojo extends AbstractMojo {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    /** The version processes run now: {@code current}, a path, or a git ref. */
    @Parameter(property = "from", required = true)
    String from;

    /** The version to move them to: {@code current} (the default), a path, or a git ref. */
    @Parameter(property = "to", defaultValue = "current")
    String to;

    /** A YAML or JSON file of explicit step mappings: {@code {<workflow id>: {<old step>: <new step>}}}. */
    @Parameter(property = "mapping")
    File mapping;

    /** Only this workflow. */
    @Parameter(property = "workflow")
    String workflow;

    /** Directory holding workflow definitions. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/workflows")
    File workflowsDirectory;

    /** What a relative {@code from}, {@code to} or {@code mapping} path is relative to. */
    @Parameter(defaultValue = "${project.basedir}")
    File basedir;

    /** Fail the build when a step of the old version has no compatible counterpart (otherwise only warn). */
    @Parameter(property = "eventconductor.migration.failOnError", defaultValue = "true")
    boolean failOnError;

    /** Skip the check. */
    @Parameter(property = "eventconductor.migration.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("EventConductor migration check skipped (eventconductor.migration.skip=true).");
            return;
        }
        if (from == null || from.isBlank()) {
            throw new MojoExecutionException("Say which version to check from: -Dfrom=<git ref | path | current>.");
        }
        var source = read(from.strip());
        var target = read(to == null || to.isBlank() ? "current" : to.strip());
        var mappings = mappings();

        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        int checked = 0;
        for (var entry : source.entrySet()) {
            var id = entry.getKey();
            if (workflow != null && !workflow.isBlank() && !workflow.equals(id)) {
                continue;
            }
            var next = target.get(id);
            if (next == null) {
                warnings.add(id + ": not in " + describe(to) + "; its processes cannot be migrated, only finished");
                continue;
            }
            checked++;
            for (var problem : MigrationRules.checkDefinitions(steps(entry.getValue()), steps(next),
                    mappings.getOrDefault(id, Map.of()))) {
                (problem.severity() == MigrationRules.Severity.ERROR ? errors : warnings).add(id + ": " + problem);
            }
        }
        warnings.forEach(w -> getLog().warn(w));
        var summary = "EventConductor migration check " + describe(from) + " → " + describe(to) + ": " + checked
                + " workflow(s), " + errors.size() + " error(s), " + warnings.size() + " warning(s).";
        if (!errors.isEmpty()) {
            var report = summary + "\n\n" + String.join("\n", errors);
            if (failOnError) {
                throw new MojoFailureException(report);
            }
            getLog().warn(report);
            return;
        }
        getLog().info(summary);
    }

    private static String describe(String version) {
        return version == null || version.isBlank() ? "current" : version.strip();
    }

    /** Every workflow of a version, by id: its parsed definition. */
    Map<String, Map<?, ?>> read(String version) throws MojoExecutionException {
        if ("current".equalsIgnoreCase(version)) {
            return fromPath(workflowsDirectory.toPath());
        }
        var path = Path.of(version);
        if (!path.isAbsolute() && basedir != null) {
            path = basedir.toPath().resolve(version);
        }
        if (Files.exists(path)) {
            return fromPath(path);
        }
        return fromGit(version);
    }

    private Map<String, Map<?, ?>> fromPath(Path path) throws MojoExecutionException {
        var definitions = new TreeMap<String, Map<?, ?>>();
        if (!Files.exists(path)) {
            return definitions;
        }
        var root = Files.isDirectory(path) ? path : path.getParent();
        for (var file : files(path)) {
            try {
                put(definitions, root, file, Files.readAllBytes(file));
            } catch (IOException e) {
                throw new MojoExecutionException("Could not read " + file, e);
            }
        }
        return definitions;
    }

    /** The workflows directory's definition files as they were at {@code ref}; a file the ref lacks is left out. */
    private Map<String, Map<?, ?>> fromGit(String ref) throws MojoExecutionException {
        if (workflowsDirectory == null || !workflowsDirectory.isDirectory()) {
            throw new MojoExecutionException("'" + ref + "' is neither a path nor readable from git: no workflows"
                    + " directory at " + workflowsDirectory);
        }
        var directory = workflowsDirectory.toPath().toAbsolutePath().normalize();
        var top = git(directory, "rev-parse", "--show-toplevel");
        if (top == null) {
            throw new MojoExecutionException("'" + ref + "' is not a path, and " + directory + " is not in a git"
                    + " repository to read it from.");
        }
        if (git(directory, "rev-parse", "--verify", "--quiet", ref + "^{commit}") == null) {
            throw new MojoExecutionException("'" + ref + "' is neither a path nor a git ref of " + top.strip() + ".");
        }
        var gitRoot = Path.of(top.strip()).toAbsolutePath().normalize();
        try {
            gitRoot = gitRoot.toRealPath();
            directory = directory.toRealPath();
        } catch (IOException e) {
            // keep the normalised paths
        }
        // From the top of the repository, where the paths ls-tree prints and show takes are rooted.
        var listed = git(gitRoot, "ls-tree", "-r", "--name-only", ref, "--", gitRoot.relativize(directory).toString());
        var definitions = new TreeMap<String, Map<?, ?>>();
        if (listed == null) {
            return definitions;
        }
        for (var line : listed.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var file = gitRoot.resolve(line.strip());
            if (!ValidateMojo.isDefinitionFile(file)) {
                continue;
            }
            var content = git(gitRoot, "show", ref + ":" + line.strip());
            if (content != null) {
                put(definitions, directory, file, content.getBytes(StandardCharsets.UTF_8));
            }
        }
        return definitions;
    }

    private void put(Map<String, Map<?, ?>> definitions, Path root, Path file, byte[] content)
            throws MojoExecutionException {
        Map<?, ?> definition;
        try {
            var mapper = file.getFileName().toString().toLowerCase().endsWith(".json") ? JSON : YAML;
            definition = mapper.readValue(content, Map.class);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not parse " + file + ": " + e.getMessage(), e);
        }
        if (definition == null) {
            return;
        }
        var id = definition.get("id") instanceof String s && !s.isBlank() ? s : ValidateMojo.derivedId(root, file);
        definitions.put(id, definition);
    }

    private static List<Path> files(Path path) throws MojoExecutionException {
        if (!Files.isDirectory(path)) {
            return List.of(path);
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).filter(ValidateMojo::isDefinitionFile).sorted().toList();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not scan " + path, e);
        }
    }

    private static List<Map<?, ?>> steps(Map<?, ?> definition) {
        var steps = definition.get("steps");
        if (!(steps instanceof List<?> list)) {
            return List.of();
        }
        var maps = new ArrayList<Map<?, ?>>();
        list.stream().filter(Map.class::isInstance).forEach(step -> maps.add((Map<?, ?>) step));
        return maps;
    }

    private Map<String, Map<String, String>> mappings() throws MojoExecutionException {
        var mappings = new LinkedHashMap<String, Map<String, String>>();
        if (mapping == null) {
            return mappings;
        }
        var file = mapping.isAbsolute() || basedir == null ? mapping : new File(basedir, mapping.getPath());
        Map<?, ?> raw;
        try {
            raw = (file.getName().toLowerCase().endsWith(".json") ? JSON : YAML).readValue(file, Map.class);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not read the mapping file " + file + ": " + e.getMessage(), e);
        }
        if (raw == null) {
            return mappings;
        }
        raw.forEach((workflowId, steps) -> {
            var byStep = new LinkedHashMap<String, String>();
            if (steps instanceof Map<?, ?> m) {
                m.forEach((source, target) -> byStep.put(String.valueOf(source), target == null ? "" : String.valueOf(target)));
            }
            mappings.put(String.valueOf(workflowId), byStep);
        });
        return mappings;
    }

    /** A git command's standard output, or null when it failed. */
    static String git(Path directory, String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false).start();
            var out = new ByteArrayOutputStream();
            try (var in = process.getInputStream()) {
                in.transferTo(out);
            }
            process.getErrorStream().transferTo(OutputStream.nullOutputStream());
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
