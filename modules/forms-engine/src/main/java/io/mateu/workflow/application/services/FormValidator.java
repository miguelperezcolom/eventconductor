package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.mateu.workflow.domain.Form;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a {@link Form} against the JSON schema defined in {@code form-schema.json}.
 *
 * <p>Throws {@link FormValidationException} if any violation is found.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormValidator {

    private static final String SCHEMA_RESOURCE = "form-schema.json";

    private final ObjectMapper objectMapper;
    private com.networknt.schema.JsonSchema schema;

    @PostConstruct
    void init() throws IOException {
        try (var stream = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(stream);
            log.debug("Loaded form JSON schema from classpath:{}", SCHEMA_RESOURCE);
        }
    }

    public void validate(Form form) {
        try {
            var node = objectMapper.valueToTree(form);
            Set<ValidationMessage> violations = schema.validate(node);
            if (!violations.isEmpty()) {
                String details = violations.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("\n"));
                throw new FormValidationException(
                        "Form '" + form.name() + "' is invalid:\n" + details);
            }
        } catch (FormValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new FormValidationException(
                    "Could not validate form: " + e.getMessage(), e);
        }
    }

    public static class FormValidationException extends RuntimeException {
        public FormValidationException(String message) {
            super(message);
        }
        public FormValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
