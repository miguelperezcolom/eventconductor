package io.mateu.workflow.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The type of a task attribute (see {@link TaskAttribute}). Serialized in a {@code .ectask} as the
 * lowercase token the schema declares ({@code string}, {@code integer}, …); the constants are
 * uppercase because {@code boolean} is a Java keyword and cannot name an enum constant. The
 * {@link JsonValue}/{@link JsonCreator} pair is what bridges the two, the same way {@code StepType}
 * bridges its own aliases.
 */
public enum TaskType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    DATE,
    DATETIME,
    /** An opaque JSON object — no nested contract, carried as-is. */
    OBJECT,
    /** A list; its element type is the attribute's {@code items}. */
    ARRAY;

    @JsonValue
    public String token() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static TaskType fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
