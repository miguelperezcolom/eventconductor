package io.mateu.workflow.analysis;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules of a definition's {@code retention} — how long a finished process of it is kept before
 * the purge job deletes it — shared as code by the engine and the Maven plugin, so a retention the
 * build accepts is one the engine purges by.
 *
 * <p>Two shapes, as written in the file:
 * <ul>
 *   <li>one value for every terminal status: {@code retention: P30D};</li>
 *   <li>a value per terminal status: {@code retention: { completed: P30D, error: P180D }} — the keys are
 *       {@link #STATUS_KEYS}; a status not listed falls back to the engine's defaults.</li>
 * </ul>
 *
 * <p>Each value is an ISO-8601 duration in days, hours, minutes and seconds ({@code P30D},
 * {@code PT12H}, {@code P1DT12H}) counted from the moment the process ended, or {@value #FOREVER}
 * — never purge, whatever the engine's defaults say. Months and years are not durations (their
 * length depends on the calendar): write {@code P365D}. Zero and negative are refused.
 */
public final class RetentionRules {

    /** The value that keeps a process forever, overriding any engine default. */
    public static final String FOREVER = "forever";

    /** The per-status keys, in the order the terminal statuses are documented. */
    public static final List<String> STATUS_KEYS =
            List.of("completed", "cancelled", "error", "compensated", "compensationFailed");

    private static final Pattern ISO_DAYS_TO_SECONDS =
            Pattern.compile("P(\\d+D)?(T(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?");

    private RetentionRules() {
    }

    /**
     * The retention a value means: empty for {@value #FOREVER}, the duration otherwise.
     *
     * @throws IllegalArgumentException if the value is not a retention
     */
    public static Optional<Duration> parse(String value) {
        var problem = problem(value);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        var trimmed = value.trim();
        return FOREVER.equals(trimmed) ? Optional.empty() : Optional.of(Duration.parse(trimmed));
    }

    /** Whether this is a retention value (a positive ISO-8601 duration, or {@value #FOREVER}). */
    public static boolean isValid(String value) {
        return problem(value) == null;
    }

    /**
     * What is wrong with a definition's retention, each message prefixed with {@code where}; empty
     * means valid. Pass {@code all} for the single-value shape, {@code byStatus} for the per-status
     * one (or both null: no retention declared).
     */
    public static List<String> violations(String all, Map<String, String> byStatus, String where) {
        var violations = new ArrayList<String>();
        var prefix = where == null || where.isBlank() ? "" : where + ": ";
        if (all != null && byStatus != null) {
            violations.add(prefix + "either one retention for every status or one per status, not both");
        }
        if (all != null) {
            var problem = problem(all);
            if (problem != null) {
                violations.add(prefix + problem);
            }
        }
        if (byStatus != null) {
            if (byStatus.isEmpty()) {
                violations.add(prefix + "a per-status retention must name at least one status ("
                        + String.join(", ", STATUS_KEYS) + ")");
            }
            byStatus.forEach((status, value) -> {
                if (!STATUS_KEYS.contains(status)) {
                    violations.add(prefix + "'" + status + "' is not a terminal status (one of "
                            + String.join(", ", STATUS_KEYS) + ")");
                    return;
                }
                var problem = problem(value);
                if (problem != null) {
                    violations.add(prefix + status + ": " + problem);
                }
            });
        }
        return violations;
    }

    private static String problem(String value) {
        if (value == null || value.isBlank()) {
            return "a retention must be an ISO-8601 duration such as P30D, or '" + FOREVER + "'";
        }
        var trimmed = value.trim();
        if (FOREVER.equals(trimmed)) {
            return null;
        }
        if (!ISO_DAYS_TO_SECONDS.matcher(trimmed).matches() || "P".equals(trimmed) || trimmed.endsWith("T")) {
            return "'" + value + "' is not a retention: write an ISO-8601 duration in days, hours, minutes or"
                    + " seconds (P30D, PT12H, P1DT12H — months and years are not durations: write P365D),"
                    + " or '" + FOREVER + "'";
        }
        try {
            var duration = Duration.parse(trimmed);
            if (duration.isZero() || duration.isNegative()) {
                return "'" + value + "' is not a retention: it must be longer than zero";
            }
            return null;
        } catch (DateTimeParseException | ArithmeticException e) {
            return "'" + value + "' is not a retention: " + e.getMessage();
        }
    }
}
