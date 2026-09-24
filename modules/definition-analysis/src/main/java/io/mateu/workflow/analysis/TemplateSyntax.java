package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The syntax of a payload template, without evaluating anything: where the {@code ${…}} expressions
 * are in a string, and whether they are well formed. JDK-only so the Maven plugin, the engine and the
 * HTTP worker read templates identically; the expressions themselves are JEXL, parsed and evaluated
 * by whoever holds a JEXL engine.
 *
 * <ul>
 *   <li>{@code ${expr}} — an expression. Braces inside it may nest ({@code ${ {'a': 1} }}) and quoted
 *       strings inside it may contain braces.</li>
 *   <li>{@code $${} — a literal {@code ${}.</li>
 *   <li>A string that is exactly one expression is a <em>typed</em> leaf: its value keeps its type.</li>
 * </ul>
 */
public final class TemplateSyntax {

    /** A literal run of text, or the source of one expression. */
    public record Segment(boolean expression, String text) {
    }

    /** A template that is not well formed. */
    public static final class TemplateSyntaxException extends IllegalArgumentException {
        public TemplateSyntaxException(String message) {
            super(message);
        }
    }

    private TemplateSyntax() {
    }

    /** Whether this string contains any expression (or escape) at all. */
    public static boolean isTemplate(String text) {
        return text != null && text.contains("${");
    }

    /** The segments of {@code text}, in order. */
    public static List<Segment> parse(String text) {
        var segments = new ArrayList<Segment>();
        var literal = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$${", i)) {
                literal.append("${");
                i += 3;
                continue;
            }
            if (text.startsWith("${", i)) {
                int end = closingBrace(text, i + 2);
                if (end < 0) {
                    throw new TemplateSyntaxException("Unterminated ${ at position " + i + " in \"" + abbreviate(text) + "\"");
                }
                var source = text.substring(i + 2, end).trim();
                if (source.isEmpty()) {
                    throw new TemplateSyntaxException("Empty ${} at position " + i + " in \"" + abbreviate(text) + "\"");
                }
                if (!literal.isEmpty()) {
                    segments.add(new Segment(false, literal.toString()));
                    literal.setLength(0);
                }
                segments.add(new Segment(true, source));
                i = end + 1;
                continue;
            }
            literal.append(text.charAt(i));
            i++;
        }
        if (!literal.isEmpty()) {
            segments.add(new Segment(false, literal.toString()));
        }
        return segments;
    }

    /** The expression, if {@code text} is exactly one {@code ${…}} (a typed leaf); else null. */
    public static String singleExpression(String text) {
        if (text == null) {
            return null;
        }
        var trimmed = text.strip();
        if (!trimmed.startsWith("${") || trimmed.startsWith("$${")) {
            return null;
        }
        var segments = parse(trimmed);
        return segments.size() == 1 && segments.getFirst().expression() ? segments.getFirst().text() : null;
    }

    /**
     * Every expression in a structured template (maps, lists, strings; anything else is a constant),
     * each with where it is, for validation messages. Throws on a malformed string.
     */
    public static List<Map.Entry<String, String>> expressions(Object template, String where) {
        var found = new ArrayList<Map.Entry<String, String>>();
        collect(template, where, found);
        return found;
    }

    private static void collect(Object node, String where, List<Map.Entry<String, String>> found) {
        if (node instanceof String text) {
            if (isTemplate(text)) {
                for (var segment : parse(text)) {
                    if (segment.expression()) {
                        found.add(Map.entry(where, segment.text()));
                    }
                }
            }
        } else if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> collect(value, where + "." + key, found));
        } else if (node instanceof Collection<?> list) {
            int index = 0;
            for (var item : list) {
                collect(item, where + "[" + index++ + "]", found);
            }
        }
    }

    /** Index of the brace closing an expression whose body starts at {@code from}; -1 if none. */
    private static int closingBrace(String text, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    private static String abbreviate(String text) {
        return text.length() > 80 ? text.substring(0, 77) + "..." : text;
    }
}
