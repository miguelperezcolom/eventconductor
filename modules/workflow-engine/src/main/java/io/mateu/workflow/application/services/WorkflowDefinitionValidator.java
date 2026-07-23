package io.mateu.workflow.application.services;

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
        try (var stream = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(stream);
            log.debug("Loaded workflow definition JSON schema from classpath:{}", SCHEMA_RESOURCE);
        }
    }

    public void validate(WorkflowDefinition definition) {
        definition.checkInvariants();
        if (definition.cronExpression() != null && !definition.cronExpression().isBlank()
                && !org.springframework.scheduling.support.CronExpression.isValidExpression(definition.cronExpression().trim())) {
            throw new WorkflowDefinitionValidationException(
                    "Workflow definition '" + definition.name() + "' is invalid:\n'"
                            + definition.cronExpression() + "' is not a valid cron expression");
        }
        try {
            var violations = schema.validate(objectMapper.writeValueAsString(definition), InputFormat.JSON);
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
