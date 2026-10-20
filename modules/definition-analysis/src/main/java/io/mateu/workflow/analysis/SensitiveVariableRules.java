package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of a definition's sensitive variables — the variables whose values the engine stores and
 * transports only encrypted, masks in the console, REST and MCP, and keeps out of search attributes and
 * LLM prompts — shared as code by the engine and the Maven plugin, so a declaration the build accepts is
 * one the engine protects.
 *
 * <pre>
 * sensitiveVariables: [cardNumber, "patient.*"]
 * steps:
 *   - id: tokenize
 *     outputVariables: [pan, cvv]
 *     sensitiveOutputs: true        # this step's outputVariables are sensitive too
 * </pre>
 *
 * <ul>
 *   <li>A pattern is a variable name, or a glob: {@code *} matches any run of characters (dots
 *       included), {@code ?} exactly one. {@code patient.*} also matches {@code patient} itself — the
 *       variable whose value holds those fields. Matching is case-sensitive, as variable names are.</li>
 *   <li>At most {@value #MAX_PATTERNS} patterns of at most {@value #MAX_PATTERN_LENGTH} characters, each
 *       once.</li>
 *   <li>A step's {@code sensitiveOutputs: true} needs {@code outputVariables} to protect.</li>
 *   <li>A search attribute may not read a sensitive variable (an index of plaintext would be a leak), and
 *       an LLM_CALL may not put one in its prompt unless it opts in with
 *       {@code llm.allowSensitiveVariables: true}.</li>
 * </ul>
 */
public final class SensitiveVariableRules {

    /** How many patterns one definition may declare. */
    public static final int MAX_PATTERNS = 100;

    /** The longest pattern. */
    public static final int MAX_PATTERN_LENGTH = 128;

    /** What every masked value reads as, wherever it is shown. */
    public static final String MASK = "••••";

    private static final Pattern PATTERN = Pattern.compile("[A-Za-z_$*?][A-Za-z0-9_$.*?\\-]*");

    /** A dotted identifier path in an expression: {@code patient.address.city}. */
    private static final Pattern PATH = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** A string literal in an expression, removed before looking for identifiers. */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"");

    private SensitiveVariableRules() {
    }

    /** Problems with a {@code sensitiveVariables} list; empty when it is fine (or absent). */
    public static List<String> violations(Collection<?> patterns, String where) {
        var problems = new ArrayList<String>();
        if (patterns == null) {
            return problems;
        }
        var label = where == null ? "sensitiveVariables" : where;
        if (patterns.size() > MAX_PATTERNS) {
            problems.add(label + ": at most " + MAX_PATTERNS + " patterns may be declared, found " + patterns.size());
        }
        var seen = new HashSet<String>();
        for (var raw : patterns) {
            if (!(raw instanceof String pattern) || pattern.isBlank()) {
                problems.add(label + ": every entry must be a variable name or a glob");
                continue;
            }
            var trimmed = pattern.trim();
            if (trimmed.length() > MAX_PATTERN_LENGTH) {
                problems.add(label + ": '" + abbreviate(trimmed) + "' is longer than " + MAX_PATTERN_LENGTH + " characters");
            } else if (!PATTERN.matcher(trimmed).matches()) {
                problems.add(label + ": '" + trimmed + "' is not a variable name or a glob (letters, digits, _ $ . - and the wildcards * ?)");
            } else if (!seen.add(trimmed)) {
                problems.add(label + ": '" + trimmed + "' is declared more than once");
            }
        }
        return problems;
    }

    /** Problems with a step's {@code sensitiveOutputs} flag. */
    public static List<String> stepViolations(Boolean sensitiveOutputs, Collection<String> outputVariables, String where) {
        if (Boolean.TRUE.equals(sensitiveOutputs) && (outputVariables == null || outputVariables.isEmpty())) {
            return List.of(where + ": sensitiveOutputs is true but the step declares no outputVariables to protect");
        }
        return List.of();
    }

    /**
     * Every pattern a definition protects: its {@code sensitiveVariables} plus the {@code outputVariables}
     * of each step that says {@code sensitiveOutputs: true}. Trimmed, in order, each once.
     */
    public static List<String> effectivePatterns(Collection<String> sensitiveVariables,
                                                 Collection<? extends Collection<String>> sensitiveStepOutputs) {
        var all = new LinkedHashSet<String>();
        if (sensitiveVariables != null) {
            sensitiveVariables.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).forEach(all::add);
        }
        if (sensitiveStepOutputs != null) {
            for (var outputs : sensitiveStepOutputs) {
                if (outputs != null) {
                    outputs.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).forEach(all::add);
                }
            }
        }
        return List.copyOf(all);
    }

    /**
     * The effective patterns of a definition in its generic (parsed JSON/YAML) form: {@code sensitiveVariables}
     * plus the {@code outputVariables} of each step with {@code sensitiveOutputs: true}.
     */
    public static List<String> patternsOf(Map<?, ?> definition) {
        if (definition == null) {
            return List.of();
        }
        var declared = new ArrayList<String>();
        if (definition.get("sensitiveVariables") instanceof Collection<?> list) {
            list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(declared::add);
        }
        var outputs = new ArrayList<List<String>>();
        if (definition.get("steps") instanceof Collection<?> steps) {
            for (var step : steps) {
                if (step instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("sensitiveOutputs"))
                        && map.get("outputVariables") instanceof Collection<?> names) {
                    outputs.add(names.stream().filter(String.class::isInstance).map(String.class::cast).toList());
                }
            }
        }
        return effectivePatterns(declared, outputs);
    }

    /** A matcher over the patterns; matches nothing when there are none. */
    public static Predicate<String> matcher(Collection<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return name -> false;
        }
        var exact = new HashSet<String>();
        var globs = new ArrayList<Pattern>();
        for (var pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            var trimmed = pattern.trim();
            if (trimmed.indexOf('*') < 0 && trimmed.indexOf('?') < 0) {
                exact.add(trimmed);
            } else {
                globs.add(Pattern.compile(globToRegex(trimmed)));
                if (trimmed.endsWith(".*") && trimmed.length() > 2) {
                    // patient.* protects patient itself: the variable holding those fields.
                    var root = trimmed.substring(0, trimmed.length() - 2);
                    if (root.indexOf('*') < 0 && root.indexOf('?') < 0) {
                        exact.add(root);
                    } else {
                        globs.add(Pattern.compile(globToRegex(root)));
                    }
                }
            }
        }
        if (globs.isEmpty()) {
            return name -> name != null && exact.contains(name);
        }
        return name -> name != null && (exact.contains(name) || globs.stream().anyMatch(g -> g.matcher(name).matches()));
    }

    /** Whether {@code name} matches the single {@code pattern}. */
    public static boolean matches(String pattern, String name) {
        return matcher(List.of(pattern)).test(name);
    }

    /**
     * The variables an expression reads that the matcher calls sensitive: every dotted identifier path in
     * it, and each of its prefixes ({@code patient.ssn} is read through {@code patient}). String literals
     * are not identifiers. Best effort — a name built at runtime ({@code vars[name]}) is not seen.
     */
    public static Set<String> sensitiveReferences(String expression, Predicate<String> sensitive) {
        var found = new LinkedHashSet<String>();
        if (expression == null || expression.isBlank()) {
            return found;
        }
        var stripped = STRING_LITERAL.matcher(expression).replaceAll(" ");
        Matcher matcher = PATH.matcher(stripped);
        while (matcher.find()) {
            // A method call or a member after a closing bracket (x[0].y) is not a variable path.
            if (matcher.start() > 0 && stripped.charAt(matcher.start() - 1) == '.') {
                continue;
            }
            var path = matcher.group();
            var prefix = new StringBuilder();
            for (var part : path.split("\\.")) {
                if (!prefix.isEmpty()) {
                    prefix.append('.');
                }
                prefix.append(part);
                var candidate = prefix.toString();
                if (sensitive.test(candidate)) {
                    found.add(candidate);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * The search attributes that read a sensitive variable — refused: an index is plaintext by
     * definition, and an index over ciphertext could find nothing.
     */
    public static List<String> searchAttributeViolations(Map<String, ?> searchAttributes, Predicate<String> sensitive) {
        var problems = new ArrayList<String>();
        if (searchAttributes == null || searchAttributes.isEmpty()) {
            return problems;
        }
        for (var declaration : SearchAttributeRules.declarations(searchAttributes)) {
            var read = templateReferences(declaration.value(), sensitive);
            if (!read.isEmpty()) {
                problems.add("searchAttributes." + declaration.name() + " reads the sensitive variable(s) "
                        + String.join(", ", read) + ": a sensitive variable cannot be a search attribute");
            }
        }
        return problems;
    }

    /**
     * An LLM_CALL whose prompt or system message reads a sensitive variable, refused unless the step
     * opts in with {@code llm.allowSensitiveVariables: true}.
     */
    public static List<String> llmViolations(Map<?, ?> llm, Predicate<String> sensitive, String where) {
        if (llm == null || Boolean.TRUE.equals(llm.get("allowSensitiveVariables"))) {
            return List.of();
        }
        var read = new LinkedHashSet<String>();
        for (var key : List.of("system", "prompt")) {
            if (llm.get(key) instanceof String template) {
                read.addAll(templateReferences(template, sensitive));
            }
        }
        if (read.isEmpty()) {
            return List.of();
        }
        return List.of(where + ": the prompt reads the sensitive variable(s) " + String.join(", ", read)
                + "; an LLM_CALL only receives sensitive values with llm.allowSensitiveVariables: true");
    }

    /**
     * A key computed from the process — a message correlation key, a lock key, a process lock key — that
     * reads a sensitive variable: refused, like a search attribute. A key is stored in clear and looked up
     * by equality (the step's awaiting key, the lock row, the event receipt), so it would be the sensitive
     * value at rest under another name. {@code key} is a JEXL expression or a {@code ${…}} template.
     */
    public static List<String> keyViolations(String key, Predicate<String> sensitive, String where) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        var read = TemplateSyntax.isTemplate(key) ? templateReferences(key, sensitive) : sensitiveReferences(key, sensitive);
        if (read.isEmpty()) {
            return List.of();
        }
        return List.of(where + " reads the sensitive variable(s) " + String.join(", ", read)
                + ": a correlation or lock key is stored in clear, so it cannot be computed from a sensitive variable");
    }

    /** The sensitive variables a template's expressions read; empty for a malformed template. */
    public static Set<String> templateReferences(String template, Predicate<String> sensitive) {
        var found = new LinkedHashSet<String>();
        if (template == null || !TemplateSyntax.isTemplate(template)) {
            return found;
        }
        try {
            for (var segment : TemplateSyntax.parse(template)) {
                if (segment.expression()) {
                    found.addAll(sensitiveReferences(segment.text(), sensitive));
                }
            }
        } catch (TemplateSyntax.TemplateSyntaxException e) {
            // Reported by the template checks; nothing to add here.
        }
        return found;
    }

    private static String globToRegex(String glob) {
        var regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
