package io.mateu.workflow.analysis;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a <em>moment</em> — {@code {date, offset, at, zone, ifPast}}, the way a TIMER's
 * {@code until} and any step's {@code deadline} say "when" from the process's data — on its generic
 * (parsed JSON/YAML) form, shared as code by the engine and the Maven plugin. Also the parser of the
 * {@code offset}, which both sides need to agree on to the character.
 *
 * <p>A moment may also be written as a plain string, which is its {@code date}.
 */
public final class MomentRules {

    public static final Set<String> IF_PAST = Set.of("fire", "timeout");
    public static final Set<String> FIELDS = Set.of("date", "offset", "at", "zone", "ifPast");

    /**
     * An ISO-8601 duration with an optional leading sign, split where the calendar and the clock
     * part: {@code -P3D} is three calendar days back, {@code PT2H} two hours of elapsed time.
     */
    private static final Pattern OFFSET = Pattern.compile(
            "([+-])?P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?"
                    + "(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d{1,9})?)S)?)?");

    /** An offset: the calendar part (days and up, applied in the moment's zone) and the clock part. */
    public record Offset(Period period, Duration duration) {
        public static final Offset NONE = new Offset(Period.ZERO, Duration.ZERO);
    }

    /**
     * Parses an offset such as {@code -P3D}, {@code P1DT6H} or {@code -PT30M}.
     *
     * @throws IllegalArgumentException when it is not one
     */
    public static Offset parseOffset(String text) {
        if (text == null || text.isBlank()) {
            return Offset.NONE;
        }
        var trimmed = text.trim().toUpperCase();
        var matcher = OFFSET.matcher(trimmed);
        if (!matcher.matches() || trimmed.endsWith("P") || trimmed.endsWith("T")) {
            throw new IllegalArgumentException("'" + text + "' is not an ISO-8601 offset such as -P3D, P1DT6H or -PT30M.");
        }
        int sign = "-".equals(matcher.group(1)) ? -1 : 1;
        var period = Period.of(number(matcher.group(2)), number(matcher.group(3)),
                number(matcher.group(4)) * 7 + number(matcher.group(5)));
        var duration = Duration.ofHours(number(matcher.group(6))).plusMinutes(number(matcher.group(7)));
        if (matcher.group(8) != null) {
            duration = duration.plus(Duration.parse("PT" + matcher.group(8) + "S"));
        }
        return sign < 0 ? new Offset(period.negated(), duration.negated()) : new Offset(period, duration);
    }

    /** Parses a time of day ({@code 09:00}, {@code 09:00:30}). */
    public static LocalTime parseAt(String text) {
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + text + "' is not a time of day such as 09:00.", e);
        }
    }

    /**
     * Problems with a moment; {@code moment} is its generic form (a map, or a string that is its
     * date; null when absent). {@code allowIfPast} is false for a deadline, which has no past policy
     * of its own — a deadline already past is simply reached.
     */
    public static List<String> problems(Object moment, String where, boolean allowIfPast) {
        var problems = new ArrayList<String>();
        if (moment == null) {
            return problems;
        }
        if (moment instanceof String date) {
            checkDate(date, where, problems);
            return problems;
        }
        if (!(moment instanceof Map<?, ?> map)) {
            problems.add(where + " must be a date template or an object {date, offset, at, zone"
                    + (allowIfPast ? ", ifPast" : "") + "}.");
            return problems;
        }
        for (var key : map.keySet()) {
            if (!FIELDS.contains(String.valueOf(key)) || (!allowIfPast && "ifPast".equals(key))) {
                problems.add(where + " has an unknown field '" + key + "'.");
            }
        }
        var date = map.get("date");
        if (date == null || String.valueOf(date).isBlank()) {
            problems.add(where + " must define a date.");
        } else {
            checkDate(String.valueOf(date), where, problems);
        }
        var offset = map.get("offset");
        if (offset != null) {
            try {
                parseOffset(String.valueOf(offset));
            } catch (IllegalArgumentException e) {
                problems.add(where + " offset: " + e.getMessage());
            }
        }
        var at = map.get("at");
        if (at != null) {
            try {
                parseAt(String.valueOf(at));
            } catch (IllegalArgumentException e) {
                problems.add(where + " at: " + e.getMessage());
            }
        }
        var zone = map.get("zone");
        if (zone != null) {
            var text = String.valueOf(zone);
            if (TemplateSyntax.isTemplate(text)) {
                checkTemplate(text, where + " zone", problems);
            } else {
                try {
                    ZoneId.of(text);
                } catch (DateTimeException e) {
                    problems.add(where + " zone '" + text + "' is not a time zone (use an IANA name such as Europe/Madrid).");
                }
            }
        }
        var ifPast = map.get("ifPast");
        if (ifPast != null && !IF_PAST.contains(String.valueOf(ifPast))) {
            problems.add(where + " ifPast must be one of " + IF_PAST + ".");
        }
        return problems;
    }

    private static void checkDate(String date, String where, List<String> problems) {
        if (date.isBlank()) {
            problems.add(where + " must define a date.");
        } else if (TemplateSyntax.isTemplate(date)) {
            checkTemplate(date, where + " date", problems);
        }
    }

    private static void checkTemplate(String text, String where, List<String> problems) {
        try {
            TemplateSyntax.parse(text);
        } catch (TemplateSyntax.TemplateSyntaxException e) {
            problems.add(where + ": " + e.getMessage());
        }
    }

    private static int number(String group) {
        return group == null ? 0 : Integer.parseInt(group);
    }

    private MomentRules() {
    }
}
