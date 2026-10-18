package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The structural rules of a definition's event {@code triggers} and of a {@code WAIT_FOR_MESSAGE}'s
 * {@code onEvent} — shared as code by the engine and the Maven plugin, so a trigger the build accepts
 * is one the engine starts processes from, and vice versa. Templates and filters are checked by
 * whoever holds a JEXL engine; these are the rules that need none.
 *
 * <ul>
 *   <li>{@code destination} — a logical destination ({@code workflow.events.destinations.<name>}),
 *       required; letters, digits, {@code .}, {@code _} and {@code -}.</li>
 *   <li>{@code type} — the CloudEvents type to match, required. {@code *} matches any run of
 *       characters ({@code com.acme.booking.*}); a lone {@code *} matches every event, typed or not.</li>
 *   <li>{@code id} — optional, unique in the definition, the schedule id syntax. Absent, it is derived
 *       from the type ({@link #effectiveIds}).</li>
 * </ul>
 *
 * <p>The same triggers, each naming the workflow it starts, can live in an {@code events.yaml} next to
 * the definitions ({@link #isEventFileName}) — the counterpart of {@code cron.yaml} for schedules.
 */
public final class EventTriggerRules {

    /** The longest trigger id accepted (the schedule limit). */
    public static final int MAX_ID_LENGTH = ScheduleRules.MAX_ID_LENGTH;

    /**
     * The names of the file that declares event triggers outside the definitions, next to them: never
     * a workflow definition, whatever its extension says.
     */
    public static final Set<String> EVENT_FILE_NAMES = Set.of("events.yaml", "events.yml");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private EventTriggerRules() {
    }

    /** Whether this is a trigger id; null or blank means "none declared" and is fine. */
    public static boolean isValidId(String id) {
        return ScheduleRules.isValidId(id);
    }

    /** Whether a file name (or path; only the last segment counts) is an event-trigger file. */
    public static boolean isEventFileName(String name) {
        if (name == null) {
            return false;
        }
        var normalized = name.replace('\\', '/');
        var slash = normalized.lastIndexOf('/');
        var last = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return EVENT_FILE_NAMES.contains(last.toLowerCase(Locale.ROOT));
    }

    /** Whether this names a destination (the same syntax as a destination key in configuration). */
    public static boolean isValidDestination(String destination) {
        return destination != null && !destination.isBlank() && NAME.matcher(destination.trim()).matches();
    }

    /** What is wrong with one trigger, each message prefixed with {@code where}; empty means valid. */
    public static List<String> triggerViolations(String id, String destination, String type, String where) {
        var violations = new ArrayList<String>();
        var prefix = prefix(where);
        if (!isValidId(id)) {
            violations.add(prefix + "'" + id + "' is not a valid trigger id (letters, digits, '.', '_' and '-',"
                    + " starting with a letter or digit, at most " + MAX_ID_LENGTH + " characters)");
        }
        violations.addAll(sourceViolations(destination, type, where));
        return violations;
    }

    /** What is wrong with the destination and type of a trigger or an {@code onEvent}. */
    public static List<String> sourceViolations(String destination, String type, String where) {
        var violations = new ArrayList<String>();
        var prefix = prefix(where);
        if (destination == null || destination.isBlank()) {
            violations.add(prefix + "a destination is required (a name under workflow.events.destinations)");
        } else if (!isValidDestination(destination)) {
            violations.add(prefix + "'" + destination + "' is not a valid destination name (letters, digits,"
                    + " '.', '_' and '-')");
        }
        if (type == null || type.isBlank()) {
            violations.add(prefix + "a type is required (the CloudEvents type, e.g. com.acme.booking.created,"
                    + " or a pattern with '*')");
        }
        return violations;
    }

    /** {@code onEvent} is only read on a {@code WAIT_FOR_MESSAGE}: anywhere else it would be ignored. */
    public static List<String> onEventPlacementViolations(String stepId, String stepType, boolean hasOnEvent) {
        if (hasOnEvent && !"WAIT_FOR_MESSAGE".equals(stepType)) {
            return List.of("Step '" + stepId + "' declares onEvent, which only a WAIT_FOR_MESSAGE reads"
                    + (AwaitReplyRules.STEP_TYPES.contains(stepType)
                    ? " (" + AwaitReplyRules.withArticle(stepType) + " waiting for a reply takes it inside awaitReply)." : "."));
        }
        return List.of();
    }

    /** The ids declared more than once in one definition's triggers, each reported once. */
    public static List<String> duplicateIdViolations(List<String> ids, String where) {
        var seen = new java.util.HashSet<String>();
        var reported = new LinkedHashSet<String>();
        for (var id : ids) {
            if (id != null && !id.isBlank() && !seen.add(id.trim())) {
                reported.add(id.trim());
            }
        }
        var prefix = prefix(where);
        return reported.stream().map(id -> prefix + "trigger id '" + id + "' is declared more than once").toList();
    }

    /**
     * The id of each trigger, in order: its own when declared, otherwise derived from its type (the
     * characters an id cannot hold become {@code -}), with {@code -2}, {@code -3}… on a repeat. Derived
     * from content rather than position, so reordering the list does not rename a trigger (its pause and
     * its business keys go with the id).
     *
     * @param declaredIds the declared id of each trigger (null when none)
     * @param types       the type of each trigger, same order
     */
    public static List<String> effectiveIds(List<String> declaredIds, List<String> types) {
        var taken = new java.util.HashSet<String>();
        for (var id : declaredIds) {
            if (id != null && !id.isBlank()) {
                taken.add(id.trim());
            }
        }
        var counts = new HashMap<String, Integer>();
        var ids = new ArrayList<String>();
        for (int i = 0; i < declaredIds.size(); i++) {
            var declared = declaredIds.get(i);
            if (declared != null && !declared.isBlank()) {
                ids.add(declared.trim());
                continue;
            }
            var base = derivedBase(i < types.size() ? types.get(i) : null);
            var candidate = base;
            int n = counts.getOrDefault(base, 1);
            while (taken.contains(candidate)) {
                n++;
                candidate = base + "-" + n;
            }
            counts.put(base, n);
            taken.add(candidate);
            ids.add(candidate);
        }
        return ids;
    }

    /**
     * The id derived from a type for a trigger that declares none, before any {@code -2} suffix: what
     * {@link #effectiveIds} gives a lone trigger of that type.
     */
    public static String derivedId(String type) {
        return derivedBase(type);
    }

    private static String derivedBase(String type) {
        if (type == null || type.isBlank()) {
            return "trigger";
        }
        var cleaned = type.trim().replace("*", "any").replaceAll("[^A-Za-z0-9._-]", "-")
                .replaceAll("^[^A-Za-z0-9]+", "");
        if (cleaned.isEmpty()) {
            return "trigger";
        }
        return cleaned.length() > MAX_ID_LENGTH - 4 ? cleaned.substring(0, MAX_ID_LENGTH - 4) : cleaned;
    }

    /**
     * Whether an event of type {@code type} matches the pattern a trigger declares. {@code *} matches
     * any run of characters; a lone {@code *} also matches an event without a type (a plain record).
     * Otherwise the comparison is exact and case-sensitive, as CloudEvents types are.
     */
    public static boolean typeMatches(String pattern, String type) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        var p = pattern.trim();
        if ("*".equals(p)) {
            return true;
        }
        if (type == null) {
            return false;
        }
        if (!p.contains("*")) {
            return p.equals(type);
        }
        var regex = new StringBuilder();
        var parts = p.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.toString()).matcher(type).matches();
    }

    private static String prefix(String where) {
        return where == null || where.isBlank() ? "" : where + ": ";
    }
}
