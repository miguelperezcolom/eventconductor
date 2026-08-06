package io.mateu.workflowbench.soak;

import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Puts the soak's workflow definition into the database the deployed engine reads.
 *
 * <p>The engine imports definitions from its own classpath at boot, which is fine when the engine
 * is something the harness starts. Here it is a published image that has never heard of this
 * benchmark, so the definition has to arrive from outside.
 *
 * <p>It writes SQL rather than hand-rolling the row, but it builds the {@code steps_json} column
 * by asking the engine's own serializer — the column holds a polymorphic {@code List<Step>} and
 * a hand-written literal would be a second, silently diverging copy of that format.
 *
 * <p>Unlike the engine's importer this one overwrites. That is deliberate: replacing a definition
 * while processes are mid-flight is one of the failure modes under test, and it needs to be a
 * single reproducible command.
 */
public final class WorkflowInstaller {

    private static final String UPSERT = """
            INSERT INTO workflow_definition_entity
              (id, name, version, description, steps_json,
               limit_concurrent_executions, max_concurrent_executions, enqueue_on_limit,
               cron_expression, default_max_step_executions, paused, declared_status, runtime_status)
            VALUES (?, ?, ?, ?, ?, false, 0, false, NULL, 0, false, 'ACTIVE', 'ACTIVE')
            ON CONFLICT (id) DO UPDATE SET
              name = excluded.name,
              version = excluded.version,
              description = excluded.description,
              steps_json = excluded.steps_json""";

    /**
     * @param resource classpath location of the definition, e.g. {@code /workflows/bench-3-steps.json}
     * @return the definition id that was written
     */
    public static String install(JdbcTemplate jdbc, String resource) {
        var definition = read(resource);
        var steps = definition.steps().stream()
                .map(step -> step.withWorkflowDefinitionId(definition.id()))
                .toList();
        jdbc.update(UPSERT,
                definition.id(),
                definition.name(),
                definition.version(),
                definition.description(),
                toJson(steps));
        return definition.id();
    }

    private static WorkflowDefinition read(String resource) {
        try (var in = WorkflowInstaller.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(in, () -> "No such definition on the classpath: " + resource);
            return pojoFromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    WorkflowDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    private WorkflowInstaller() {
    }
}
