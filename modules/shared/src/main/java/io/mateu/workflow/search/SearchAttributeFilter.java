package io.mateu.workflow.search;

import io.mateu.workflow.analysis.SearchAttributeRules;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One condition on a search attribute — {@code customerId = C-9}, {@code amount >= 100} — as every door
 * takes it (the process list's attribute box, {@code attr.<name>=} on the REST API, the MCP tools, a
 * batch operation's filter) and every store applies it.
 *
 * <p>The filter does not know the attribute's type (two definitions may declare the same name with
 * different ones); it is matched against what each process has stored:
 * <ul>
 *   <li>{@link Op#EQ} matches a keyword or bool exactly, a text as a case-insensitive substring, a number
 *       equal to the value read as a number, a date equal to the value read as a date.</li>
 *   <li>The ranges ({@link Op#GT}, {@link Op#GTE}, {@link Op#LT}, {@link Op#LTE}) compare numbers and dates
 *       only: the value must read as one or the other, or the filter is refused.</li>
 * </ul>
 * Values are always bound as parameters, never written into a query.
 */
public record SearchAttributeFilter(String name, Op op, String value) {

    /** How the stored value is compared with {@link #value}. */
    public enum Op {
        EQ("="), GT(">"), GTE(">="), LT("<"), LTE("<=");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /** Whether this is a range (numbers and dates only). */
        public boolean isRange() {
            return this != EQ;
        }

        /** The operator a REST suffix names: {@code gt}, {@code gte}, {@code lt}, {@code lte}; null for none. */
        public static Op ofSuffix(String suffix) {
            if (suffix == null || suffix.isBlank()) {
                return EQ;
            }
            return switch (suffix.toLowerCase(Locale.ROOT)) {
                case "eq" -> EQ;
                case "gt" -> GT;
                case "gte" -> GTE;
                case "lt" -> LT;
                case "lte" -> LTE;
                default -> throw new IllegalArgumentException("Unknown attribute operator '" + suffix
                        + "'; one of eq, gt, gte, lt, lte.");
            };
        }
    }

    /** The longest value a filter compares with. */
    public static final int MAX_VALUE_LENGTH = SearchAttributeRules.MAX_VALUE_LENGTH;

    /** How many attribute conditions one listing may carry. */
    public static final int MAX_FILTERS = 10;

    public SearchAttributeFilter {
        if (!SearchAttributeRules.isValidName(name)) {
            throw new IllegalArgumentException("'" + name + "' is not a search attribute name (a letter, then"
                    + " letters, digits and '_', at most " + SearchAttributeRules.MAX_NAME_LENGTH + " characters).");
        }
        if (op == null) {
            op = Op.EQ;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Search attribute '" + name + "' needs a value to compare with.");
        }
        value = value.strip();
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("The value for search attribute '" + name + "' is longer than "
                    + MAX_VALUE_LENGTH + " characters.");
        }
        // The fields are not assigned yet in a compact constructor: read the parameters, not the accessors.
        if (op.isRange() && SearchAttributeValue.number(value).isEmpty()
                && SearchAttributeValue.moment(value, false).isEmpty()) {
            throw new IllegalArgumentException("'" + name + " " + op.symbol() + " " + value + "': a range compares"
                    + " numbers and dates only, and '" + value + "' is neither.");
        }
    }

    /** The value read as a number, when it is one. */
    public Optional<Double> number() {
        return SearchAttributeValue.number(value);
    }

    /**
     * The value read as a moment, when it is one; a bare date bounding from above ({@code <=}, {@code >})
     * stands for its last moment, so {@code date <= 2026-10-18} includes that whole day.
     */
    public Optional<LocalDateTime> date() {
        return SearchAttributeValue.moment(value, op == Op.LTE || op == Op.GT);
    }

    /** The value lower-cased, for a text attribute's substring match. */
    public String textPattern() {
        return value.toLowerCase(Locale.ROOT);
    }

    /** Whether one stored value satisfies this condition. */
    public boolean matches(SearchAttributeValue stored) {
        if (stored == null || !name.equals(stored.name())) {
            return false;
        }
        return switch (stored.type()) {
            case KEYWORD, BOOL -> op == Op.EQ && value.equals(stored.keyword());
            case TEXT -> op == Op.EQ && stored.keyword() != null
                    && stored.keyword().toLowerCase(Locale.ROOT).contains(textPattern());
            case NUMBER -> stored.number() != null && number().map(n -> compare(stored.number().compareTo(n))).orElse(false);
            case DATE -> stored.date() != null && date().map(d -> compare(stored.date().compareTo(d))).orElse(false);
        };
    }

    private boolean compare(int comparison) {
        return switch (op) {
            case EQ -> comparison == 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
        };
    }

    /** Whether a process's values satisfy every condition (an empty list of conditions: always). */
    public static boolean matchAll(Collection<SearchAttributeFilter> filters, Collection<SearchAttributeValue> values) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        var stored = values == null ? List.<SearchAttributeValue>of() : values;
        return filters.stream().allMatch(filter -> stored.stream().anyMatch(filter::matches));
    }

    /** Refuses more conditions than a listing takes. */
    public static List<SearchAttributeFilter> checked(List<SearchAttributeFilter> filters) {
        if (filters == null) {
            return List.of();
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException(filters.size() + " search attribute conditions; at most " + MAX_FILTERS + ".");
        }
        return List.copyOf(filters);
    }

    @Override
    public String toString() {
        return name + " " + op.symbol() + " " + SearchQuery.quote(value);
    }
}
