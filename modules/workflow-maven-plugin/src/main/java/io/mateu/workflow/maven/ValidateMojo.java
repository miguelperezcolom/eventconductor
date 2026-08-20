package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Validates EventConductor workflow, form and rule definitions (JSON or YAML) against their
 * published specifications, failing the build on any violation.
 *
 * <p>By default it scans, under the project's resources, {@code workflows/}, {@code forms/}
 * and {@code rules/} for {@code *.ec}, {@code *.ecform}, {@code *.ecrule}, {@code *.json},
 * {@code *.yaml} and {@code *.yml} files — the same six the engine imports, and the same layout
 * it loads from the classpath. Each directory is validated against the matching specification.
 */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class ValidateMojo extends AbstractMojo {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    /** Directory holding workflow definitions. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/workflows")
    private File workflowsDirectory;

    /** Directory holding form definitions. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/forms")
    private File formsDirectory;

    /** Directory holding rule definitions. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/rules")
    private File rulesDirectory;

    /** Validate workflow definitions. */
    @Parameter(property = "eventconductor.validate.workflows", defaultValue = "true")
    private boolean validateWorkflows;

    /** Validate form definitions. */
    @Parameter(property = "eventconductor.validate.forms", defaultValue = "true")
    private boolean validateForms;

    /** Validate rule definitions. */
    @Parameter(property = "eventconductor.validate.rules", defaultValue = "true")
    private boolean validateRules;

    /** Fail the build when a definition is invalid (otherwise only warn). */
    @Parameter(property = "eventconductor.validate.failOnError", defaultValue = "true")
    private boolean failOnError;

    /** Fail the build when a configured directory has no definitions at all. */
    @Parameter(property = "eventconductor.validate.failOnMissing", defaultValue = "false")
    private boolean failOnMissing;

    /** Skip validation entirely. */
    @Parameter(property = "eventconductor.validate.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("EventConductor definition validation skipped (eventconductor.validate.skip=true).");
            return;
        }

        SpecValidator validator = new SpecValidator();
        List<String> failures = new ArrayList<>();
        int validated = 0;

        if (validateWorkflows) {
            validated += validateDirectory(validator, SpecValidator.Kind.WORKFLOW, workflowsDirectory, failures);
        }
        if (validateForms) {
            validated += validateDirectory(validator, SpecValidator.Kind.FORM, formsDirectory, failures);
        }
        if (validateRules) {
            validated += validateDirectory(validator, SpecValidator.Kind.RULE, rulesDirectory, failures);
        }

        if (!failures.isEmpty()) {
            String report = "EventConductor definition validation found " + failures.size()
                    + " problem(s):\n\n" + String.join("\n", failures);
            if (failOnError) {
                throw new MojoFailureException(report);
            }
            getLog().warn(report);
            return;
        }

        getLog().info("EventConductor: " + validated + " definition(s) validated successfully.");
    }

    private int validateDirectory(SpecValidator validator, SpecValidator.Kind kind, File directory,
                                  List<String> failures) throws MojoExecutionException, MojoFailureException {
        if (directory == null || !directory.isDirectory()) {
            if (failOnMissing) {
                throw new MojoFailureException("No " + kind.name().toLowerCase()
                        + " directory found at " + directory);
            }
            getLog().debug("Skipping " + kind + " validation, no directory at " + directory);
            return 0;
        }

        List<Path> files = listDefinitionFiles(directory.toPath());
        if (files.isEmpty() && failOnMissing) {
            throw new MojoFailureException("No " + kind.name().toLowerCase()
                    + " definitions found under " + directory);
        }

        int count = 0;
        for (Path file : files) {
            count++;
            JsonNode document;
            try {
                document = parse(file);
            } catch (IOException e) {
                failures.add(file + ": could not parse (" + e.getMessage() + ")");
                continue;
            }
            List<String> violations = validator.validate(kind, document);
            if (violations.isEmpty()) {
                getLog().debug("Valid " + kind + ": " + file);
            } else {
                failures.add(file + ":\n  - " + String.join("\n  - ", violations));
            }
            // Warnings never fail the build, regardless of failOnError.
            validator.warnings(kind, document)
                    .forEach(warning -> getLog().warn(file + ": " + warning));
        }
        return count;
    }

    private static List<Path> listDefinitionFiles(Path directory) throws MojoExecutionException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(ValidateMojo::isDefinitionFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not scan " + directory, e);
        }
    }

    /**
     * The extensions a definition is written with.
     *
     * <p>Deliberately a copy of {@code DerivedIds.DEFINITION_EXTENSIONS} in the engine's shared
     * module, and not a reference to it: a Maven plugin runs inside the build's own classloader,
     * and that module brings Spring, Flyway and the messaging stack with it — a dependency this
     * has no business having. <b>The two lists have to move together.</b> They already failed to
     * once: this one was missing {@code .ec}, {@code .ecform} and {@code .ecrule}, which are what
     * the graph editor and both IDE plugins write, so pointing the validator at a real repository
     * of definitions found nothing and passed.
     */
    private static final List<String> DEFINITION_EXTENSIONS =
            List.of(".ec", ".ecform", ".ecrule", ".json", ".yaml", ".yml");

    private static boolean isDefinitionFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return DEFINITION_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * Reads a definition, whatever it is written in.
     *
     * <p>Everything but {@code .json} goes to the YAML parser, which reads JSON too — YAML is a
     * superset of it. That is the same rule {@code ImportFormsFromDirectoryUseCase} follows, and it
     * is what keeps a new extension from needing a decision here: an {@code .ec} holding JSON and
     * an {@code .ec} holding YAML both parse, which is exactly what the editors produce.
     */
    private static JsonNode parse(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        ObjectMapper mapper = name.endsWith(".json") ? JSON : YAML;
        return mapper.readTree(file.toFile());
    }
}
