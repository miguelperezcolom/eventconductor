package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.mateu.workflow.analysis.RetentionRules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How long a finished process of a definition is kept before the purge job deletes it — the
 * definition's {@code retention}, as written in the file.
 *
 * <p>Two shapes, never both: {@link #all()} — {@code retention: P30D}, one value for every terminal
 * status — or one value per terminal status — {@code retention: { completed: P30D, error: P180D }}.
 * A status the per-status shape does not list is not decided here: it falls back to the engine's
 * defaults ({@code workflow.processes.retention*}). Each value is an ISO-8601 duration counted from
 * the process end, or {@code forever}; see {@link RetentionRules}.
 *
 * <p>Serialised the way it is written — a string, or an object of the listed statuses only — so a
 * definition round-trips through the exporter unchanged.
 */
public record ProcessRetention(String all, String completed, String cancelled, String error,
                               String compensated, String compensationFailed) {

    /** One value for every terminal status. */
    public static ProcessRetention of(String all) {
        return new ProcessRetention(all, null, null, null, null, null);
    }

    /** One value per listed terminal status (keys as in the file: {@link RetentionRules#STATUS_KEYS}). */
    public static ProcessRetention perStatus(Map<String, String> byStatus) {
        var unknown = byStatus.keySet().stream().filter(k -> !RetentionRules.STATUS_KEYS.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("retention: " + unknown + " are not terminal statuses (one of "
                    + String.join(", ", RetentionRules.STATUS_KEYS) + ")");
        }
        return new ProcessRetention(null, byStatus.get("completed"), byStatus.get("cancelled"),
                byStatus.get("error"), byStatus.get("compensated"), byStatus.get("compensationFailed"));
    }

    /** Reads either shape; what Jackson calls for {@code retention}. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ProcessRetention fromJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return of(text);
        }
        if (value instanceof Map<?, ?> map) {
            var byStatus = new LinkedHashMap<String, String>();
            map.forEach((k, v) -> byStatus.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            return perStatus(byStatus);
        }
        throw new IllegalArgumentException("retention must be a duration such as P30D or an object of"
                + " durations per terminal status, not " + value);
    }

    /** Writes it as it was read: the single value, or the listed statuses only. */
    @JsonValue
    public Object toJson() {
        return all != null ? all : byStatus();
    }

    /** The per-status values that are set, keyed as in the file; null in the single-value shape. */
    public Map<String, String> byStatus() {
        if (all != null) {
            return null;
        }
        var map = new LinkedHashMap<String, String>();
        put(map, "completed", completed);
        put(map, "cancelled", cancelled);
        put(map, "error", error);
        put(map, "compensated", compensated);
        put(map, "compensationFailed", compensationFailed);
        return map;
    }

    /** What is wrong with it ({@link RetentionRules}); empty means valid. */
    public List<String> violations(String where) {
        return RetentionRules.violations(all, all != null ? null : byStatus(), where);
    }

    /**
     * What this declares for a status, as written ({@code P30D}, {@code forever}): empty when it says
     * nothing about it, and the engine defaults decide. Non-terminal statuses are never purged, so
     * nothing is declared for them.
     */
    public Optional<String> declaredFor(ProcessStatus status) {
        var value = all != null ? (isTerminal(status) ? all : null) : switch (status) {
            case COMPLETED -> completed;
            case CANCELLED -> cancelled;
            case ERROR -> error;
            case COMPENSATED -> compensated;
            case COMPENSATION_FAILED -> compensationFailed;
            default -> null;
        };
        return Optional.ofNullable(value);
    }

    /** The statuses a process can end in, and so the only ones retention ever applies to. */
    public static final List<ProcessStatus> TERMINAL = List.of(ProcessStatus.COMPLETED, ProcessStatus.CANCELLED,
            ProcessStatus.ERROR, ProcessStatus.COMPENSATED, ProcessStatus.COMPENSATION_FAILED);

    public static boolean isTerminal(ProcessStatus status) {
        return TERMINAL.contains(status);
    }

    /** As the console shows it: {@code P30D}, or {@code completed P30D · error P180D}. */
    @Override
    public String toString() {
        if (all != null) {
            return all;
        }
        var parts = new java.util.ArrayList<String>();
        byStatus().forEach((k, v) -> parts.add(k + " " + v));
        return String.join(" · ", parts);
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
