package io.mateu.workflow.application.out;

/**
 * Outgoing port for rule-catalog observability metrics.
 *
 * All methods are no-ops by default so the catalog runs unchanged when no metrics
 * backend is configured. When Micrometer and a {@code MeterRegistry} bean are
 * present, {@code RuleCatalogMetricsAutoConfiguration} provides an implementation
 * that publishes these signals as Micrometer meters.
 *
 * Instrumented at the use-case layer, so the same metrics are emitted in all
 * deployment modes (embedded, kafka, gRPC-served).
 */
public interface RuleCatalogMetrics {

    RuleCatalogMetrics NOOP = new RuleCatalogMetrics() {};

    default void ruleSaved(String ruleId) {}

    default void ruleDeleted(String ruleId) {}

    default void rulesImported(long count) {}

    default void ruleServed(String ruleId, String source) {}
}
