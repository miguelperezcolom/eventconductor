package io.mateu.workflow.crypto;

import io.mateu.workflow.analysis.SensitiveVariableRules;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which variable names are sensitive — the compiled form of a definition's {@code sensitiveVariables}
 * (and its steps' {@code sensitiveOutputs}), see {@link SensitiveVariableRules}.
 */
public final class SensitiveNames implements Predicate<String> {

    /** Nothing is sensitive. */
    public static final SensitiveNames NONE = new SensitiveNames(List.of());

    private final List<String> patterns;
    private final Predicate<String> matcher;

    private SensitiveNames(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
        this.matcher = SensitiveVariableRules.matcher(this.patterns);
    }

    /** The names matching these patterns (names or globs). */
    public static SensitiveNames of(Collection<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return NONE;
        }
        return new SensitiveNames(List.copyOf(new LinkedHashSet<>(patterns.stream()
                .filter(p -> p != null && !p.isBlank()).map(String::trim).toList())));
    }

    /** These names plus {@code more}. */
    public SensitiveNames plus(Collection<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        var all = new LinkedHashSet<>(patterns);
        all.addAll(more);
        return of(all);
    }

    /** Whether nothing is sensitive. */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** The patterns, as declared. */
    public List<String> patterns() {
        return patterns;
    }

    @Override
    public boolean test(String name) {
        return !patterns.isEmpty() && matcher.test(name);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SensitiveNames names && names.patterns.equals(patterns);
    }

    @Override
    public int hashCode() {
        return patterns.hashCode();
    }

    @Override
    public String toString() {
        return "SensitiveNames" + patterns;
    }
}
