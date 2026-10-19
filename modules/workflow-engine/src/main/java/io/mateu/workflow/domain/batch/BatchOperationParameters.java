package io.mateu.workflow.domain.batch;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A batch's {@code parameters} from what every door asks for — the variables to set and remove (SET_VARIABLES),
 * the step (RETRY_FROM_STEP), the reason — so the REST API, the MCP tools and the console write them alike.
 */
public final class BatchOperationParameters {

    private BatchOperationParameters() {
    }

    /** The parameters; null and blank inputs are left out. */
    public static Map<String, String> of(Map<String, String> set, Collection<String> remove, String stepId, String reason) {
        var parameters = new LinkedHashMap<String, String>();
        if (set != null) {
            set.forEach((name, value) -> parameters.put(BatchOperationType.SET_PREFIX + name, value));
        }
        if (remove != null) {
            remove.stream().filter(name -> name != null && !name.isBlank())
                    .forEach(name -> parameters.put(BatchOperationType.REMOVE_PREFIX + name.strip(), ""));
        }
        if (stepId != null && !stepId.isBlank()) {
            parameters.put(BatchOperationType.STEP_ID, stepId.strip());
        }
        if (reason != null && !reason.isBlank()) {
            parameters.put(BatchOperationType.REASON, reason.strip());
        }
        return parameters;
    }

    /** {@code name=value} lines (the console's text box) as a map, in order; a line without {@code =} is refused. */
    public static Map<String, String> assignments(String text) {
        var set = new LinkedHashMap<String, String>();
        if (text == null) {
            return set;
        }
        for (var line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("'" + line.strip() + "' is not name=value.");
            }
            set.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return set;
    }
}
