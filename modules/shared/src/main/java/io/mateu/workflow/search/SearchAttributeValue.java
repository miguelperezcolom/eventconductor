package io.mateu.workflow.search;

import io.mateu.workflow.analysis.SearchAttributeRules;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One indexed search attribute of a process: its name, its type and its value in the one column the
 * type uses — {@code keyword} for keyword, text and bool; {@code number}; {@code date}. The same shape
 * in every store (the process tables, the process index, memory) and on the
 * {@code ProcessStatusChanged} event that projects it.
 */
public record SearchAttributeValue(String name, SearchAttributeType type, String keyword, Double number,
                                   LocalDateTime date) {

    /**
     * The value of an attribute of {@code type} from what its expression produced — empty when there is
     * nothing to index: null, blank, or not a value of the type (a "12a" for a number). A keyword or text
     * longer than {@link SearchAttributeRules#MAX_VALUE_LENGTH} is cut to that length.
     */
    public static Optional<SearchAttributeValue> of(String name, SearchAttributeType type, Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        var text = String.valueOf(raw).strip();
        if (text.isEmpty() || "null".equals(text)) {
            return Optional.empty();
        }
        return switch (type) {
            case KEYWORD, TEXT -> Optional.of(new SearchAttributeValue(name, type, cut(text), null, null));
            case BOOL -> bool(text).map(b -> new SearchAttributeValue(name, type, b.toString(), null, null));
            case NUMBER -> number(raw).map(n -> new SearchAttributeValue(name, type, null, n, null));
            case DATE -> moment(text, false).map(d -> new SearchAttributeValue(name, type, null, null, d));
        };
    }

    /** The value as text, whatever its type — for display. */
    public String display() {
        return switch (type) {
            case NUMBER -> number == null ? null : BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
            case DATE -> date == null ? null : date.toString();
            default -> keyword;
        };
    }

    /** Whether two sets of values say the same thing, whatever their order. */
    public static boolean sameValues(Collection<SearchAttributeValue> a, Collection<SearchAttributeValue> b) {
        var left = a == null ? java.util.Set.<SearchAttributeValue>of() : java.util.Set.copyOf(a);
        var right = b == null ? java.util.Set.<SearchAttributeValue>of() : java.util.Set.copyOf(b);
        return Objects.equals(left, right);
    }

    private static String cut(String text) {
        return text.length() <= SearchAttributeRules.MAX_VALUE_LENGTH
                ? text : text.substring(0, SearchAttributeRules.MAX_VALUE_LENGTH);
    }

    /** {@code true}/{@code false}, case-insensitively; anything else is not a boolean. */
    static Optional<Boolean> bool(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) {
            return Optional.of(Boolean.TRUE);
        }
        if ("false".equals(lower)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    /** A finite number from a number or its text. */
    static Optional<Double> number(Object raw) {
        double value;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else {
            try {
                value = new BigDecimal(String.valueOf(raw).strip()).doubleValue();
            } catch (NumberFormatException notANumber) {
                return Optional.empty();
            }
        }
        return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
    }

    /**
     * A moment from ISO text: a date-time with an offset or a {@code Z} (converted to the JVM's zone, as
     * every timestamp the engine stores), a local date-time, or a date — its first moment, or its last
     * when {@code endOfDay} (an inclusive upper bound).
     */
    public static Optional<LocalDateTime> moment(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        var trimmed = text.strip();
        try {
            return Optional.of(OffsetDateTime.parse(trimmed).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        } catch (DateTimeParseException notOffset) {
            // next shape
        }
        try {
            return Optional.of(LocalDateTime.ofInstant(Instant.parse(trimmed), ZoneId.systemDefault()));
        } catch (DateTimeParseException notInstant) {
            // next shape
        }
        try {
            return Optional.of(LocalDateTime.parse(trimmed));
        } catch (DateTimeParseException notLocal) {
            // next shape
        }
        try {
            var day = LocalDate.parse(trimmed);
            return Optional.of(endOfDay ? day.atTime(java.time.LocalTime.MAX) : day.atStartOfDay());
        } catch (DateTimeParseException notADate) {
            return Optional.empty();
        }
    }
}
