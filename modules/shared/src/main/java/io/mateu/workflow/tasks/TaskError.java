package io.mateu.workflow.tasks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A business error a task may report. The {@link #code()} is a valid Java identifier so it maps to
 * a generated exception type; when a handler throws it, the step fails with the code as its reason.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskError(String code, String description) {
}
