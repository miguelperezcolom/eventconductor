package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a definition's {@code searchAttributes} — the named values a process of it is indexed
 * by, so the process list, the REST API, the MCP tools and batch operations can find it — shared as
 * code by the engine and the Maven plugin, so a declaration the build accepts is one the engine indexes.
 *
 * <p>Two shapes per attribute, as written in the file:
 * <pre>
 * searchAttributes:
 *   customerId: "${customerId}"                  # shorthand: a keyword
 *   amount: { value: "${total}", type: number }
 *   region: { value: "${address.region}" }       # type defaults to keyword
 * </pre>
 *
 * <ul>
 *   <li>A name is a letter followed by letters, digits and {@code _}, at most {@value #MAX_NAME_LENGTH}
 *       characters, each once (case-insensitively); at most {@value #MAX_ATTRIBUTES} per definition.</li>
 *   <li>A value is a template — at least one {@code ${…}} expression over the process variables — of at
 *       most {@value #MAX_EXPRESSION_LENGTH} characters.</li>
 *   <li>A type is one of {@link #TYPES}; {@code keyword} (exact match) when absent.</li>
 * </ul>
 *
 * <p>An indexed keyword or text value longer than {@value #MAX_VALUE_LENGTH} characters is cut to that
 * length; a value that is not a number (or a date, or a boolean) for its type is not indexed.
 */
public final class SearchAttributeRules {

    /** How many attributes one definition may declare. */
    public static final int MAX_ATTRIBUTES = 20;

    /** The longest attribute name. */
    public static final int MAX_NAME_LENGTH = 64;

    /** The longest indexed keyword or text value; longer values are cut to this. */
    public static final int MAX_VALUE_LENGTH = 255;

    /** The longest value expression (the template text). */
    public static final int MAX_EXPRESSION_LENGTH = 500;

    /** The attribute types, as written in the file. */
    public static final List<String> TYPES = List.of("keyword", "text", "number", "date", "bool");

    /** The type of an attribute that declares none. */
    public static final String DEFAULT_TYPE = "keyword";

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private static final Set<String> OBJECT_KEYS = Set.of("value", "type");

    private SearchAttributeRules() {
    }

    /** Whether this is an attribute name. */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && NAME.matcher(name).matches();
    }

    /** One attribute as declared: its name, its value template and its type (lower case, defaulted). */
    public record Declaration(String name, String value, String type) {
    }

    /**
     * The declarations of a {@code searchAttributes} map, in order; those that are not well formed are
     * left out (see {@link #violations}). Null for null.
     */
    public static List<Declaration> declarations(Map<String, ?> declared) {
        var out = new ArrayList<Declaration>();
        if (declared == null) {
            return out;
        }
        declared.forEach((name, spec) -> {
            if (spec instanceof String text) {
                out.add(new Declaration(name, text, DEFAULT_TYPE));
            } else if (spec instanceof Map<?, ?> map) {
                var value = map.get("value");
                var type = map.get("type");
                out.add(new Declaration(name, value == null ? null : String.valueOf(value),
                        type == null ? DEFAULT_TYPE : String.valueOf(type).trim().toLowerCase(Locale.ROOT)));
            }
        });
        return out;
    }

    /**
     * What is wrong with a definition's {@code searchAttributes}, each message prefixed with
     * {@code where}; empty means valid. Expressions are checked for their {@code ${…}} syntax only —
     * whoever holds a JEXL engine parses them (the engine does, at load).
     */
    public static List<String> violations(Map<String, ?> declared, String where) {
        var violations = new ArrayList<String>();
        if (declared == null) {
            return violations;
        }
        var prefix = where == null || where.isBlank() ? "" : where + ": ";
        if (declared.size() > MAX_ATTRIBUTES) {
            violations.add(prefix + declared.size() + " search attributes declared; at most " + MAX_ATTRIBUTES);
        }
        var seen = new java.util.HashSet<String>();
        for (var entry : new LinkedHashMap<String, Object>(declared).entrySet()) {
            var name = entry.getKey();
            if (!isValidName(name)) {
                violations.add(prefix + "'" + name + "' is not a valid search attribute name (a letter, then letters,"
                        + " digits and '_', at most " + MAX_NAME_LENGTH + " characters)");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                violations.add(prefix + "search attribute '" + name + "' is declared more than once");
            }
            var spec = entry.getValue();
            String value;
            String type = DEFAULT_TYPE;
            if (spec instanceof String text) {
                value = text;
            } else if (spec instanceof Map<?, ?> map) {
                for (var key : map.keySet()) {
                    if (!OBJECT_KEYS.contains(String.valueOf(key))) {
                        violations.add(prefix + "search attribute '" + name + "' has an unknown key '" + key
                                + "' (value, type)");
                    }
                }
                var rawValue = map.get("value");
                value = rawValue instanceof String s ? s : null;
                if (rawValue != null && value == null) {
                    violations.add(prefix + "search attribute '" + name + "': value must be a string template such as \"${"
                            + name + "}\"");
                    continue;
                }
                var rawType = map.get("type");
                if (rawType != null) {
                    type = String.valueOf(rawType).trim().toLowerCase(Locale.ROOT);
                    if (!TYPES.contains(type)) {
                        violations.add(prefix + "search attribute '" + name + "' has an unknown type '" + rawType
                                + "'; one of " + TYPES);
                    }
                }
            } else {
                violations.add(prefix + "search attribute '" + name + "' must be a template string (\"${" + name
                        + "}\") or { value, type }");
                continue;
            }
            if (value == null || value.isBlank()) {
                violations.add(prefix + "search attribute '" + name + "' has no value");
                continue;
            }
            if (value.length() > MAX_EXPRESSION_LENGTH) {
                violations.add(prefix + "search attribute '" + name + "': the value is longer than "
                        + MAX_EXPRESSION_LENGTH + " characters");
                continue;
            }
            if (!TemplateSyntax.isTemplate(value)) {
                violations.add(prefix + "search attribute '" + name + "': the value '" + value
                        + "' has no ${…} expression, so it would be the same for every process");
                continue;
            }
            try {
                TemplateSyntax.parse(value);
            } catch (TemplateSyntax.TemplateSyntaxException e) {
                violations.add(prefix + "search attribute '" + name + "': " + e.getMessage());
            }
        }
        return violations;
    }
}
