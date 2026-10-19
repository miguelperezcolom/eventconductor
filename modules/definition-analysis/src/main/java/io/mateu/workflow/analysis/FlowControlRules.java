package io.mateu.workflow.analysis;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of flow control and long-lived processes — a {@code startPolicy} (on a definition or a
 * trigger), a definition's {@code businessKeyPolicy}, a step's {@code priority} and a
 * {@code CONTINUE_AS_NEW} step's {@code continueAsNew} block — shared as code by the engine and the
 * Maven plugin, so what the build accepts is what the engine runs. Pure: generic maps and lists in,
 * messages out.
 *
 * <pre>
 * startPolicy:
 *   key: "${data.customerId}"                              # optional text template
 *   debounce: PT30S                                        # optional positive duration
 *   throttle: { limit: 10, period: PT1M, onLimit: queue }  # optional; onLimit queue (default) | drop
 *   singleton: skip                                        # optional; skip | replace
 * </pre>
 */
public final class FlowControlRules {

    /** The fields of a {@code startPolicy}. */
    public static final Set<String> START_POLICY_FIELDS = Set.of("key", "debounce", "throttle", "singleton");

    /** The fields of a {@code throttle}. */
    public static final Set<String> THROTTLE_FIELDS = Set.of("limit", "period", "onLimit");

    /** What a throttle does with a start over its limit. */
    public static final List<String> ON_LIMIT = List.of("queue", "drop");

    /** What a singleton does with a start while one is running for the key. */
    public static final List<String> SINGLETON = List.of("skip", "replace");

    /** The business-key reuse policies, the first one the default. */
    public static final List<String> BUSINESS_KEY_POLICIES =
            List.of("unique", "allowAfterFinished", "allowAfterFailed", "terminateRunning");

    /** The fields of a {@code continueAsNew} block. */
    public static final Set<String> CONTINUE_AS_NEW_FIELDS = Set.of("version", "variables");

    /** Which definition version a CONTINUE_AS_NEW starts, the first one the default. */
    public static final List<String> CONTINUE_AS_NEW_VERSIONS = List.of("same", "latest");

    /** The step types that dispatch a task to a worker, and so may carry a {@code priority}. */
    public static final Set<String> PRIORITY_STEP_TYPES =
            Set.of("ACTION", "HTTP_CALL", "LLM_CALL", "RULE", "AGENT_CALL");

    public static final int MIN_PRIORITY = 0;
    public static final int MAX_PRIORITY = 100;
    public static final int DEFAULT_PRIORITY = 50;

    /** The largest throttle limit: a policy that admits more than this per window limits nothing. */
    public static final int MAX_THROTTLE_LIMIT = 1_000_000;

    private static final Pattern ISO_DAYS_TO_SECONDS =
            Pattern.compile("P(\\d+D)?(T(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?");

    private FlowControlRules() {
    }

    // ── startPolicy ──────────────────────────────────────────────────────────────────────────────

    /**
     * What is wrong with a {@code startPolicy}, each message prefixed with {@code where}; empty means
     * valid (or absent: pass null).
     */
    public static List<String> startPolicyViolations(Object policy, String where) {
        var violations = new ArrayList<String>();
        if (policy == null) {
            return violations;
        }
        var prefix = prefix(where);
        if (!(policy instanceof Map<?, ?> map)) {
            violations.add(prefix + "a startPolicy is an object with key, debounce, throttle and/or singleton");
            return violations;
        }
        unknownFields(map, START_POLICY_FIELDS, prefix, violations);
        var key = map.get("key");
        if (key != null) {
            if (!(key instanceof String text) || text.isBlank()) {
                violations.add(prefix + "key must be a text template such as \"${data.customerId}\"");
            } else {
                templateProblem(text, prefix + "key", violations);
            }
        }
        var debounce = map.get("debounce");
        if (debounce != null) {
            var problem = durationProblem(debounce);
            if (problem != null) {
                violations.add(prefix + "debounce: " + problem);
            }
        }
        var throttle = map.get("throttle");
        if (throttle != null) {
            throttleViolations(throttle, prefix + "throttle", violations);
        }
        var singleton = map.get("singleton");
        if (singleton != null && !SINGLETON.contains(String.valueOf(singleton))) {
            violations.add(prefix + "singleton must be one of " + SINGLETON + " (was '" + singleton + "')");
        }
        if (debounce == null && throttle == null && singleton == null) {
            violations.add(prefix + "a startPolicy needs at least one of debounce, throttle or singleton");
        }
        return violations;
    }

    private static void throttleViolations(Object throttle, String where, List<String> violations) {
        if (!(throttle instanceof Map<?, ?> map)) {
            violations.add(where + ": a throttle is { limit, period, onLimit }");
            return;
        }
        unknownFields(map, THROTTLE_FIELDS, where + ": ", violations);
        var limit = map.get("limit");
        var parsed = integer(limit);
        if (parsed == null || parsed < 1 || parsed > MAX_THROTTLE_LIMIT) {
            violations.add(where + ": limit must be a whole number from 1 to " + MAX_THROTTLE_LIMIT
                    + " (was " + limit + ")");
        }
        var period = map.get("period");
        if (period == null) {
            violations.add(where + ": period is required (an ISO-8601 duration such as PT1M)");
        } else {
            var problem = durationProblem(period);
            if (problem != null) {
                violations.add(where + ": period: " + problem);
            }
        }
        var onLimit = map.get("onLimit");
        if (onLimit != null && !ON_LIMIT.contains(String.valueOf(onLimit))) {
            violations.add(where + ": onLimit must be one of " + ON_LIMIT + " (was '" + onLimit + "')");
        }
    }

    /**
     * A definition that is invocable synchronously cannot debounce its starts: the caller waits for a
     * reply from a process that would start only later, with somebody else's data.
     */
    public static List<String> syncInvocationViolations(Object policy, boolean syncInvocable, String where) {
        if (syncInvocable && policy instanceof Map<?, ?> map && map.get("debounce") != null) {
            return List.of(prefix(where) + "debounce cannot apply to a definition invocable synchronously"
                    + " (syncInvocation): its caller waits for a process that would start later with another"
                    + " start's data. Put the debounce on a trigger instead");
        }
        return List.of();
    }

    // ── businessKeyPolicy ────────────────────────────────────────────────────────────────────────

    /** What is wrong with a {@code businessKeyPolicy} value; empty when valid or absent. */
    public static List<String> businessKeyPolicyViolations(Object value, String where) {
        if (value == null || BUSINESS_KEY_POLICIES.contains(String.valueOf(value))) {
            return List.of();
        }
        return List.of(prefix(where) + "businessKeyPolicy must be one of " + BUSINESS_KEY_POLICIES
                + " (was '" + value + "')");
    }

    // ── priority ─────────────────────────────────────────────────────────────────────────────────

    /** What is wrong with a step's {@code priority}; empty when valid or absent. */
    public static List<String> priorityViolations(String stepId, String type, Object priority) {
        if (priority == null) {
            return List.of();
        }
        var violations = new ArrayList<String>();
        var parsed = integer(priority);
        if (parsed == null || parsed < MIN_PRIORITY || parsed > MAX_PRIORITY) {
            violations.add("Step '" + stepId + "': priority must be a whole number from " + MIN_PRIORITY + " to "
                    + MAX_PRIORITY + " (was " + priority + ")");
        }
        if (type != null && !PRIORITY_STEP_TYPES.contains(type)) {
            violations.add("Step '" + stepId + "': priority applies to steps that dispatch a task to a worker ("
                    + String.join(", ", PRIORITY_STEP_TYPES.stream().sorted().toList()) + "), not to " + type);
        }
        return violations;
    }

    // ── CONTINUE_AS_NEW ──────────────────────────────────────────────────────────────────────────

    /**
     * What is wrong with a step's {@code continueAsNew} block (null is fine: carry every variable to
     * the same version), and with where it sits: a CONTINUE_AS_NEW ends its process, so no step may
     * follow it, and it cannot be repeated or iterated.
     *
     * @param successors the steps that list it as a precondition
     * @param looped     whether it declares forEach, repeat or loopBack
     */
    public static List<String> continueAsNewViolations(String stepId, String type, Object block,
                                                       Collection<String> successors, boolean looped) {
        var violations = new ArrayList<String>();
        var where = "Step '" + stepId + "'";
        if (!"CONTINUE_AS_NEW".equals(type)) {
            if (block != null) {
                violations.add(where + ": continueAsNew applies only to CONTINUE_AS_NEW steps");
            }
            return violations;
        }
        if (successors != null && !successors.isEmpty()) {
            violations.add(where + ": a CONTINUE_AS_NEW ends its process, so no step may follow it (followed by "
                    + String.join(", ", successors) + ")");
        }
        if (looped) {
            violations.add(where + ": a CONTINUE_AS_NEW cannot declare forEach, repeat or loopBack");
        }
        if (block == null) {
            return violations;
        }
        if (!(block instanceof Map<?, ?> map)) {
            violations.add(where + ": continueAsNew is an object with version and variables");
            return violations;
        }
        unknownFields(map, CONTINUE_AS_NEW_FIELDS, where + " continueAsNew: ", violations);
        var version = map.get("version");
        if (version != null && !CONTINUE_AS_NEW_VERSIONS.contains(String.valueOf(version))) {
            violations.add(where + ": continueAsNew.version must be one of " + CONTINUE_AS_NEW_VERSIONS
                    + " (was '" + version + "')");
        }
        var variables = map.get("variables");
        if (variables instanceof Collection<?> names) {
            for (var name : names) {
                if (!(name instanceof String text) || text.isBlank()) {
                    violations.add(where + ": continueAsNew.variables lists variable names (found " + name + ")");
                }
            }
        } else if (variables instanceof Map<?, ?> mapped) {
            mapped.forEach((name, template) -> {
                if (name == null || String.valueOf(name).isBlank()) {
                    violations.add(where + ": continueAsNew.variables has a blank name");
                }
                if (template instanceof String text) {
                    templateProblem(text, where + " continueAsNew.variables." + name, violations);
                }
            });
        } else if (variables != null) {
            violations.add(where + ": continueAsNew.variables is a list of names to carry, or a map of"
                    + " name → template");
        }
        return violations;
    }

    // ── durations ────────────────────────────────────────────────────────────────────────────────

    /** The duration a value means. @throws IllegalArgumentException if it is not a positive ISO-8601 duration */
    public static Duration duration(Object value) {
        var problem = durationProblem(value);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return Duration.parse(String.valueOf(value).trim());
    }

    private static String durationProblem(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return "must be an ISO-8601 duration such as PT30S, PT1M or P1D (was " + value + ")";
        }
        var trimmed = text.trim();
        if (!ISO_DAYS_TO_SECONDS.matcher(trimmed).matches() || "P".equals(trimmed) || trimmed.endsWith("T")) {
            return "'" + text + "' is not an ISO-8601 duration in days, hours, minutes or seconds (PT30S, PT1M, P1D)";
        }
        try {
            var duration = Duration.parse(trimmed);
            if (duration.isZero() || duration.isNegative()) {
                return "'" + text + "' must be longer than zero";
            }
            return null;
        } catch (DateTimeParseException | ArithmeticException e) {
            return "'" + text + "' is not a duration: " + e.getMessage();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static void templateProblem(String text, String where, List<String> violations) {
        try {
            TemplateSyntax.parse(text);
        } catch (TemplateSyntax.TemplateSyntaxException e) {
            violations.add(where + ": " + e.getMessage());
        }
    }

    private static void unknownFields(Map<?, ?> map, Set<String> known, String prefix, List<String> violations) {
        for (var field : map.keySet()) {
            if (!known.contains(String.valueOf(field))) {
                violations.add(prefix + "unknown field '" + field + "' (expected "
                        + String.join(", ", known.stream().sorted().toList()) + ")");
            }
        }
    }

    /** A whole number from a number or its text; null for anything else. */
    public static Integer integer(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) <= Integer.MAX_VALUE) {
            return (int) n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String prefix(String where) {
        return where == null || where.isBlank() ? "" : where + ": ";
    }
}
