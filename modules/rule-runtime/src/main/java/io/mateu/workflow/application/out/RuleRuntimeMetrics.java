package io.mateu.workflow.application.out;

import java.time.Duration;

/**
 * Outgoing port for rule-runtime observability metrics.
 *
 * All methods are no-ops by default so rule evaluation runs unchanged when no
 * metrics backend is configured. rule-runtime can run as a plain library (no
 * Spring) or via {@code RuleRuntimeAutoConfiguration}; in both cases callers
 * default to {@link #NOOP} when no implementation is supplied. When Micrometer
 * and a {@code MeterRegistry} bean are present,
 * {@code RuleRuntimeMetricsAutoConfiguration} provides an implementation that
 * publishes these signals as Micrometer meters.
 *
 * Instrumented at the evaluation service layer, so the same metrics are emitted
 * regardless of the configured rule source (classpath, rest, grpc).
 */
public interface RuleRuntimeMetrics {

    RuleRuntimeMetrics NOOP = new RuleRuntimeMetrics() {};

    /**
     * Records a single rule evaluation.
     *
     * @param ruleId   the evaluated rule id
     * @param ruleType the rule type label (e.g. {@code expression}, {@code decision-table})
     * @param outcome  one of {@code matched} / {@code nomatch} / {@code error}
     * @param duration wall-clock evaluation duration; recorded only when non-null and non-negative
     */
    default void ruleEvaluated(String ruleId, String ruleType, String outcome, Duration duration) {}

    default void cacheHit(String ruleId) {}

    default void cacheMiss(String ruleId) {}
}
