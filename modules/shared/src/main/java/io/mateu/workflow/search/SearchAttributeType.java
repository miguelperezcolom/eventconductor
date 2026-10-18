package io.mateu.workflow.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a search attribute is indexed and compared.
 *
 * <ul>
 *   <li>{@link #KEYWORD} — exact match (an id, a code, a country): {@code attr.customerId=C-9}.</li>
 *   <li>{@link #TEXT} — case-insensitive "contains" (a free-text label).</li>
 *   <li>{@link #NUMBER} — a decimal number: exact match and ranges.</li>
 *   <li>{@link #DATE} — a moment (an ISO date or date-time): exact match and ranges.</li>
 *   <li>{@link #BOOL} — {@code true} or {@code false}.</li>
 * </ul>
 */
public enum SearchAttributeType {
    KEYWORD, TEXT, NUMBER, DATE, BOOL;

    /** The keyword as a definition writes it ({@code keyword}, {@code number}, …). */
    @JsonValue
    public String keyword() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The type a definition names, case-insensitively; {@link #KEYWORD} for null. */
    @JsonCreator
    public static SearchAttributeType of(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return KEYWORD;
        }
        return valueOf(keyword.trim().toUpperCase(Locale.ROOT));
    }

    /** Whether values of this type are held as text (keyword, text, bool). */
    public boolean isTextual() {
        return this == KEYWORD || this == TEXT || this == BOOL;
    }
}
