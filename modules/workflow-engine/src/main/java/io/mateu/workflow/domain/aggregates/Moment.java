package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.mateu.workflow.time.Moments;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A moment in business terms — a TIMER's {@code until}, any step's {@code deadline}: "3 days before
 * check-in, at 09:00 hotel time". Written in the {@code .ec} file as an object, or as a plain string
 * that is its {@code date}. Rules: {@code io.mateu.workflow.analysis.MomentRules}; arithmetic:
 * {@link Moments}.
 *
 * @param date   a template rendering to an ISO date, date-time or offset date-time
 * @param offset an ISO-8601 offset, may be negative ({@code -P3D}, {@code P1DT6H})
 * @param at     a time of day to set after the offset ({@code 09:00})
 * @param zone   an IANA zone, or a template rendering to one; default {@code workflow.time.zone}
 * @param ifPast TIMER only: {@code fire} (default) or {@code timeout} when the moment has already
 *               passed as the step starts
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Moment(String date, String offset, String at, String zone, String ifPast) {

    /** {@code until: "${checkinDate}"} — the common case, a date alone. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Moment of(String date) {
        return new Moment(date, null, null, null, null);
    }

    /** Whether a moment already past when the step starts ends the timer TIMEOUT instead of firing it. */
    @JsonIgnore
    public boolean timesOutIfPast() {
        return "timeout".equals(ifPast);
    }

    /**
     * This moment on the engine's clock, against the given process variables.
     *
     * @throws IllegalArgumentException when it cannot be resolved (missing date, bad zone, …)
     */
    public LocalDateTime resolve(List<Variable> variables) {
        return resolveZoned(variables).engineTime();
    }

    /** This moment in its own zone and on the engine's clock — the first for what logs say. */
    public Moments.Resolved resolveZoned(List<Variable> variables) {
        return Moments.resolve(date, offset, at, zone, context(variables));
    }

    /** The moment as the definition wrote it, in words — for logs and the UI. */
    public String describe() {
        var text = new StringBuilder(date == null ? "?" : date);
        if (offset != null && !offset.isBlank()) {
            text.append(' ').append(offset.trim());
        }
        if (at != null && !at.isBlank()) {
            text.append(" at ").append(at.trim());
        }
        text.append(' ').append(zone == null || zone.isBlank() ? Moments.defaultZone() : zone);
        return text.toString();
    }

    /** The generic form, for the shared rules. */
    public Map<String, Object> asMap() {
        var map = new java.util.LinkedHashMap<String, Object>();
        if (date != null) map.put("date", date);
        if (offset != null) map.put("offset", offset);
        if (at != null) map.put("at", at);
        if (zone != null) map.put("zone", zone);
        if (ifPast != null) map.put("ifPast", ifPast);
        return map;
    }

    private static Map<String, Object> context(List<Variable> variables) {
        var context = new java.util.HashMap<String, Object>();
        if (variables != null) {
            variables.forEach(variable -> context.put(variable.name(), variable.value()));
        }
        context.put("now", java.time.Instant.now().toString());
        return context;
    }
}
