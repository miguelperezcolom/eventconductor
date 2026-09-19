package io.mateu.workflow.tasks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One attribute of a task's {@code input} or {@code output}: its {@link TaskType}, whether it is
 * required, an optional description, and — when the type is {@link TaskType#ARRAY} — the element
 * type in {@link #items()}. The attribute's name is the key it is stored under in the contract's
 * input/output map, so it is not repeated here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskAttribute(
        TaskType type,
        boolean required,
        String description,
        TaskAttribute items) {
}
