package io.mateu.workflow.time;

import io.mateu.workflow.analysis.MomentRules;
import io.mateu.workflow.template.Templates;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Resolves a <em>moment</em> — "3 days before check-in, at 09:00 hotel time" — to the engine's
 * clock. The rules of its fields are {@link MomentRules}; this is the arithmetic.
 *
 * <ol>
 *   <li>{@code date} is rendered as a template and parsed: an offset date-time keeps its instant;
 *       a date-time, or a date (its start of day), is read in {@code zone}.</li>
 *   <li>{@code offset} is added in {@code zone}: its calendar part ({@code P3D}) moves the date and
 *       keeps the wall-clock time across a DST change; its clock part ({@code PT2H}) is elapsed time.</li>
 *   <li>{@code at} replaces the time of day, in {@code zone} (a time that does not exist on a DST
 *       gap moves forward, as {@link ZonedDateTime} does).</li>
 *   <li>The result is expressed on the engine's clock: a server-local date-time, like every other
 *       moment the engine stores.</li>
 * </ol>
 *
 * <p>{@code zone} is itself a template (a variable holding the hotel's zone); absent, it is
 * {@link #defaultZone()} — {@code workflow.time.zone}, the JVM's zone unless configured.
 */
public final class Moments {

    private static volatile ZoneId defaultZone = ZoneId.systemDefault();

    /** The zone for dates that carry none, when a moment names none. */
    public static ZoneId defaultZone() {
        return defaultZone;
    }

    /** Set once from {@code workflow.time.zone} at startup. */
    public static void setDefaultZone(ZoneId zone) {
        defaultZone = zone == null ? ZoneId.systemDefault() : zone;
    }

    /** A moment resolved: the instant in its zone, and the same instant on the engine's clock. */
    public record Resolved(ZonedDateTime zoned, LocalDateTime engineTime) {
    }

    /**
     * Resolves a moment against a template context.
     *
     * @throws IllegalArgumentException when its date is missing or unreadable, or a field is invalid
     *                                  — the caller fails the step, as for an unreadable {@code untilVariable}
     */
    public static Resolved resolve(String date, String offset, String at, String zone, Map<String, ?> context) {
        var renderedDate = render(date, context);
        if (renderedDate == null || renderedDate.isBlank()) {
            throw new IllegalArgumentException("the date " + describe(date) + " is empty.");
        }
        var zoneId = zone(zone, context);
        var zoned = parse(renderedDate.trim(), zoneId);
        var shift = MomentRules.parseOffset(offset);
        zoned = zoned.plus(shift.period()).plus(shift.duration());
        if (at != null && !at.isBlank()) {
            zoned = zoned.with(MomentRules.parseAt(at));
        }
        return new Resolved(zoned, zoned.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
    }

    private static ZoneId zone(String zone, Map<String, ?> context) {
        if (zone == null || zone.isBlank()) {
            return defaultZone;
        }
        var rendered = render(zone, context);
        if (rendered == null || rendered.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(rendered.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("'" + rendered + "' is not a time zone.", e);
        }
    }

    private static ZonedDateTime parse(String text, ZoneId zone) {
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(zone);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text).atZone(zone);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(text).atStartOfDay(zone);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Cannot parse '" + text + "' as an ISO 8601 date or date-time.", e);
        }
    }

    private static String render(String template, Map<String, ?> context) {
        try {
            return Templates.renderText(template, context);
        } catch (Templates.TemplateException e) {
            throw new IllegalArgumentException(describe(template) + " could not be evaluated: " + e.getMessage(), e);
        }
    }

    private static String describe(String template) {
        return "'" + template + "'";
    }

    private Moments() {
    }
}
