package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Validates a {@link WorkflowDefinition} against the JSON schema
 * defined in {@code workflow-definition-schema.json}.
 *
 * <p>Throws {@link WorkflowDefinitionValidationException} if any violation is found.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowDefinitionValidator {

    private static final String SCHEMA_RESOURCE = "workflow-definition-schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private Schema schema;

    @PostConstruct
    void init() throws IOException {
        schema();
    }

    /**
     * The compiled schema, loaded on first use.
     *
     * <p>Lazily rather than only in {@link #init()} so that a validator reached before Spring has
     * post-constructed it — a unit test, a caller that news one up — validates against the schema
     * instead of throwing a {@link NullPointerException} that would read like "this definition is
     * fine". Loading is idempotent and the result is cached, so the eager call above still means
     * a broken schema resource fails at startup rather than at the first import.
     */
    private synchronized Schema schema() throws IOException {
        if (schema == null) {
            try (var stream = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
                schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(stream);
                log.debug("Loaded workflow definition JSON schema from classpath:{}", SCHEMA_RESOURCE);
            }
        }
        return schema;
    }

    /**
     * Validates the definition <em>document</em> — what the file actually says — against the schema.
     *
     * <p>{@link #validate(WorkflowDefinition)} cannot do this job, and for a while it looked as
     * though it did. It serialises the parsed record back to JSON and validates that, so by the time
     * the schema sees the document every key Jackson did not recognise has already been dropped:
     * {@code additionalProperties: false} was checking a document that could not contain an
     * additional property. A file saying {@code "retires": 3} therefore imported clean and ran with
     * no retries at all, and nothing anywhere said so — the failure mode a schema exists to prevent.
     *
     * <p>Call this on the raw tree, before it is bound to the record. It is the only check that can
     * see a typo, so it runs at every door a file comes in through: the directory import (the git
     * import is that import after a clone) and the classpath importer.
     *
     * @param document the parsed but unbound document — JSON, or YAML already read into a tree
     * @param source   what to name in the message: a file name or a classpath location
     */
    public void validateSource(JsonNode document, String source) {
        if (document == null) {
            return;
        }
        try {
            var violations = schema().validate(document.toString(), InputFormat.JSON);
            if (!violations.isEmpty()) {
                String details = violations.stream()
                        .map(v -> v.getInstanceLocation() + ": " + v.getMessage())
                        .collect(Collectors.joining("\n"));
                throw new WorkflowDefinitionValidationException(
                        "Workflow definition file '" + source + "' is invalid:\n" + details);
            }
        } catch (WorkflowDefinitionValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowDefinitionValidationException(
                    "Could not validate workflow definition file '" + source + "': " + e.getMessage(), e);
        }
    }

    public void validate(WorkflowDefinition definition) {
        definition.checkInvariants();
        // Non-fatal guidance toward the FORK/JOIN gateway model (compensation-aware).
        definition.topologyWarnings().forEach(w ->
                log.warn("Workflow definition '{}': {}", definition.name(), w));
        if (definition.cronExpression() != null && !definition.cronExpression().isBlank()
                && !org.springframework.scheduling.support.CronExpression.isValidExpression(definition.cronExpression().trim())) {
            throw new WorkflowDefinitionValidationException(
                    "Workflow definition '" + definition.name() + "' is invalid:\n'"
                            + definition.cronExpression() + "' is not a valid cron expression");
        }
        try {
            var violations = schema().validate(objectMapper.writeValueAsString(definition), InputFormat.JSON);
            if (!violations.isEmpty()) {
                String details = violations.stream()
                        .map(v -> v.getInstanceLocation() + ": " + v.getMessage())
                        .collect(Collectors.joining("\n"));
                throw new WorkflowDefinitionValidationException(
                        "Workflow definition '" + definition.name() + "' is invalid:\n" + details);
            }
        } catch (WorkflowDefinitionValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowDefinitionValidationException(
                    "Could not validate workflow definition: " + e.getMessage(), e);
        }
    }

    public static class WorkflowDefinitionValidationException extends RuntimeException {
        public WorkflowDefinitionValidationException(String message) {
            super(message);
        }
        public WorkflowDefinitionValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
