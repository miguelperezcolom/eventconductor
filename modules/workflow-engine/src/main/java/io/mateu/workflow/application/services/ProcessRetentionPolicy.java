package io.mateu.workflow.application.services;

import io.mateu.workflow.analysis.RetentionRules;
import io.mateu.workflow.domain.aggregates.ProcessRetention;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which retention applies to a finished process: the first that says something, in this order —
 *
 * <ol>
 *   <li>the definition's {@code retention} for that status (per-status shape), or for every status
 *       (single-value shape) — {@code forever} included, which keeps it whatever comes below;</li>
 *   <li>{@code workflow.processes.retention-by-status.<status>};</li>
 *   <li>{@code workflow.processes.retention};</li>
 *   <li>nothing: kept forever.</li>
 * </ol>
 *
 * <p>Only terminal statuses have a retention: a process still running, pending or paused is never
 * purged, whatever it is configured to.
 */
public class ProcessRetentionPolicy {

    private final Duration defaultRetention;
    private final Map<ProcessStatus, Duration> defaultByStatus;

    public ProcessRetentionPolicy(Duration defaultRetention, Map<String, Duration> defaultByStatus) {
        this.defaultRetention = positiveOrNull(defaultRetention, "workflow.processes.retention");
        this.defaultByStatus = new EnumMap<>(ProcessStatus.class);
        if (defaultByStatus != null) {
            defaultByStatus.forEach((key, value) -> this.defaultByStatus.put(terminalStatus(key),
                    positiveOrNull(value, "workflow.processes.retention-by-status." + key)));
        }
    }

    public static ProcessRetentionPolicy of(ProcessRetentionProperties properties) {
        return new ProcessRetentionPolicy(properties.getRetention(), properties.getRetentionByStatus());
    }

    /** The retention of a process of {@code definition} (null: a definition that no longer exists) that ended in {@code status}. */
    public Optional<Duration> retentionFor(WorkflowDefinition definition, ProcessStatus status) {
        return resolve(definition, status).duration();
    }

    /** Whether anything at all — a default or any definition — can make a process purgeable. */
    public boolean hasDefaults() {
        return defaultRetention != null || defaultByStatus.values().stream().anyMatch(d -> d != null);
    }

    /**
     * How the console says it: {@code P30D}, {@code P30D (engine default)}, {@code completed P30D ·
     * error P180D (engine default)}, or {@code kept forever}.
     */
    public String describe(WorkflowDefinition definition) {
        var resolved = new LinkedHashMap<ProcessStatus, Resolved>();
        for (var status : ProcessRetention.TERMINAL) {
            resolved.put(status, resolve(definition, status));
        }
        var distinct = resolved.values().stream().distinct().toList();
        if (distinct.size() == 1) {
            return distinct.getFirst().describe();
        }
        var parts = new ArrayList<String>();
        resolved.forEach((status, r) -> parts.add(key(status) + " " + r.describe()));
        return String.join(" · ", parts);
    }

    private Resolved resolve(WorkflowDefinition definition, ProcessStatus status) {
        if (!ProcessRetention.isTerminal(status)) {
            return new Resolved(Optional.empty(), false);
        }
        if (definition != null && definition.retention() != null) {
            var declared = definition.retention().declaredFor(status);
            if (declared.isPresent()) {
                return new Resolved(RetentionRules.parse(declared.get()), false);
            }
        }
        var byStatus = defaultByStatus.get(status);
        if (byStatus != null) {
            return new Resolved(Optional.of(byStatus), true);
        }
        return new Resolved(Optional.ofNullable(defaultRetention), defaultRetention != null);
    }

    private record Resolved(Optional<Duration> duration, boolean engineDefault) {
        String describe() {
            return duration.map(d -> iso(d) + (engineDefault ? " (engine default)" : "")).orElse("kept forever");
        }
    }

    /** A duration as a definition would write it: {@code P30D}, {@code PT12H}, {@code P1DT12H}. */
    static String iso(Duration duration) {
        var days = duration.toDays();
        var rest = duration.minusDays(days);
        if (days == 0) {
            return rest.toString();
        }
        return "P" + days + "D" + (rest.isZero() ? "" : rest.toString().substring(1));
    }

    /** The status as the file and the configuration key it: {@code compensationFailed}. */
    static String key(ProcessStatus status) {
        return RetentionRules.STATUS_KEYS.get(ProcessRetention.TERMINAL.indexOf(status));
    }

    private static ProcessStatus terminalStatus(String key) {
        var normalised = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        for (var status : ProcessRetention.TERMINAL) {
            if (status.name().replace("_", "").toLowerCase(Locale.ROOT).equals(normalised)) {
                return status;
            }
        }
        throw new IllegalArgumentException("workflow.processes.retention-by-status." + key
                + ": not a terminal status (one of " + String.join(", ", RetentionRules.STATUS_KEYS) + ")");
    }

    private static Duration positiveOrNull(Duration duration, String property) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(property + " must be longer than zero, not " + duration);
        }
        return duration;
    }
}
