package io.mateu.workflow.search;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The small query language of search attributes: conditions joined by {@code AND}.
 *
 * <pre>
 *   customerId = "C-9" AND amount &gt; 100 AND placedOn &gt;= 2026-10-01
 * </pre>
 *
 * <p>A condition is a name, an operator ({@code =}, {@code >}, {@code >=}, {@code <}, {@code <=}) and a
 * value — quoted ({@code "…"} or {@code '…'}, with {@code \} escaping the quote) or bare (no spaces).
 * {@code AND} is case-insensitive; there is no {@code OR}, no parentheses and no negation. The text is
 * parsed into {@link SearchAttributeFilter}s — a typed list, which every store binds as parameters — and
 * never reaches a database as text, so there is nothing in it to inject.
 */
public final class SearchQuery {

    /** The longest query accepted. */
    public static final int MAX_LENGTH = 2000;

    private SearchQuery() {
    }

    /**
     * The conditions of {@code query}; empty for null or blank.
     *
     * @throws IllegalArgumentException a malformed query, saying where
     */
    public static List<SearchAttributeFilter> parse(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (query.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The search query is longer than " + MAX_LENGTH + " characters.");
        }
        var parser = new Parser(query);
        var filters = new ArrayList<SearchAttributeFilter>();
        filters.add(parser.condition());
        while (!parser.atEnd()) {
            parser.keyword("AND");
            filters.add(parser.condition());
        }
        return SearchAttributeFilter.checked(filters);
    }

    /** The conditions written back as a query that parses to them. */
    public static String format(List<SearchAttributeFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        return filters.stream().map(SearchAttributeFilter::toString).collect(Collectors.joining(" AND "));
    }

    /** A value as the language writes it: bare when it can be, quoted otherwise. */
    static String quote(String value) {
        if (!value.isEmpty() && value.chars().allMatch(c -> Character.isLetterOrDigit(c) || "-_.:+/@".indexOf(c) >= 0)) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            skipSpaces();
            return at >= text.length();
        }

        SearchAttributeFilter condition() {
            skipSpaces();
            var start = at;
            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                at++;
            }
            if (start == at) {
                throw error("an attribute name");
            }
            var name = text.substring(start, at);
            skipSpaces();
            var op = operator();
            skipSpaces();
            var value = value();
            return new SearchAttributeFilter(name, op, value);
        }

        void keyword(String word) {
            skipSpaces();
            if (!text.regionMatches(true, at, word, 0, word.length())
                    || (at + word.length() < text.length() && !Character.isWhitespace(text.charAt(at + word.length())))) {
                throw error("'" + word + "'");
            }
            at += word.length();
        }

        private SearchAttributeFilter.Op operator() {
            if (text.startsWith(">=", at)) {
                at += 2;
                return SearchAttributeFilter.Op.GTE;
            }
            if (text.startsWith("<=", at)) {
                at += 2;
                return SearchAttributeFilter.Op.LTE;
            }
            if (text.startsWith(">", at)) {
                at++;
                return SearchAttributeFilter.Op.GT;
            }
            if (text.startsWith("<", at)) {
                at++;
                return SearchAttributeFilter.Op.LT;
            }
            if (text.startsWith("=", at)) {
                at++;
                return SearchAttributeFilter.Op.EQ;
            }
            throw error("an operator (=, >, >=, <, <=)");
        }

        private String value() {
            if (at >= text.length()) {
                throw error("a value");
            }
            var quote = text.charAt(at);
            if (quote == '"' || quote == '\'') {
                at++;
                var value = new StringBuilder();
                while (at < text.length()) {
                    var c = text.charAt(at++);
                    if (c == '\\' && at < text.length()) {
                        value.append(text.charAt(at++));
                    } else if (c == quote) {
                        return value.toString();
                    } else {
                        value.append(c);
                    }
                }
                throw new IllegalArgumentException("Unterminated quoted value in the search query.");
            }
            var start = at;
            while (at < text.length() && !Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            return text.substring(start, at);
        }

        private void skipSpaces() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private IllegalArgumentException error(String expected) {
            return new IllegalArgumentException("Expected " + expected + " at position " + (at + 1)
                    + " of the search query: " + text);
        }
    }
}
