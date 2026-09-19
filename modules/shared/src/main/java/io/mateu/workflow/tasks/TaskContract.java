package io.mateu.workflow.tasks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract of a worker task — what an ACTION step commits to when it references the task by id.
 * Parsed from a {@code .ectask} file (see {@code task-contract-schema.json} in this module) and
 * shared by everything that needs it: the engine (which pins the version onto a step at import and
 * routes by the default {@link #topic()}), the Maven plugin (which validates it), and the code
 * generator (which turns {@link #input()}/{@link #output()}/{@link #errors()} into records, an
 * interface and typed exceptions).
 *
 * <p>A plain record with no framework dependencies, so it can live in {@code shared} and be reused
 * from a build plugin and a generator as readily as from the engine.
 *
 * <p>{@code input} and {@code output} keep their declaration order ({@link LinkedHashMap}), because
 * that order is the order of the generated record's components and must be stable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskContract(
        String id,
        int version,
        String group,
        String topic,
        String description,
        Map<String, TaskAttribute> input,
        Map<String, TaskAttribute> output,
        List<TaskError> errors) {

    public TaskContract {
        // Absent in a file all mean "none"; copy into ordered/immutable holders so a parsed contract
        // and one built by hand compare equal and the declaration order survives.
        input = input == null ? Map.of() : new LinkedHashMap<>(input);
        output = output == null ? Map.of() : new LinkedHashMap<>(output);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** {@code <id>@<version>} — the reference the engine stamps onto a step and sends as the taskId. */
    public String ref() {
        return id + "@" + version;
    }
}
