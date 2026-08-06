package io.mateu.workflow.autoconfigure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule-catalog meters, which nothing exercised.
 *
 * <p>Names are asserted as literals, not through the constants: meter names are part of the 1.0
 * contract and their consumers — dashboards, alert rules — live outside this build, so a rename has
 * to fail here or it fails in production silently. See {@code MicrometerFormsMetricsTest} for the
 * same reasoning on the forms side.
 */
class MicrometerRuleCatalogMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerRuleCatalogMetrics metrics = new MicrometerRuleCatalogMetrics(registry);

    @Test
    void theMeterNamesAndTagsAreTheOnesTheDocumentationPromises() {
        metrics.ruleSaved("rule-1");
        metrics.ruleDeleted("rule-1");
        metrics.rulesImported(1);
        metrics.ruleServed("rule-1", "grpc");

        assertThat(registry.get("eventconductor.rule.catalog.saved").tag("ruleId", "rule-1").counter())
                .isNotNull();
        assertThat(registry.get("eventconductor.rule.catalog.deleted").tag("ruleId", "rule-1").counter())
                .isNotNull();
        assertThat(registry.get("eventconductor.rule.catalog.imported").counter()).isNotNull();
        assertThat(registry.get("eventconductor.rule.catalog.served")
                .tag("ruleId", "rule-1").tag("source", "grpc").counter()).isNotNull();
    }

    @Test
    void savesAndDeletesAreCountedPerRule() {
        metrics.ruleSaved("rule-1");
        metrics.ruleSaved("rule-1");
        metrics.ruleSaved("rule-2");
        metrics.ruleDeleted("rule-2");

        assertThat(registry.get("eventconductor.rule.catalog.saved")
                .tag("ruleId", "rule-1").counter().count()).isEqualTo(2);
        assertThat(registry.get("eventconductor.rule.catalog.saved")
                .tag("ruleId", "rule-2").counter().count()).isEqualTo(1);
        assertThat(registry.get("eventconductor.rule.catalog.deleted")
                .tag("ruleId", "rule-2").counter().count()).isEqualTo(1);
    }

    /** The point of the `source` tag: telling a runtime served over gRPC from one served over REST. */
    @Test
    void servedIsSplitBySource() {
        metrics.ruleServed("rule-1", "grpc");
        metrics.ruleServed("rule-1", "grpc");
        metrics.ruleServed("rule-1", "rest");

        assertThat(registry.get("eventconductor.rule.catalog.served")
                .tag("source", "grpc").counter().count()).isEqualTo(2);
        assertThat(registry.get("eventconductor.rule.catalog.served")
                .tag("source", "rest").counter().count()).isEqualTo(1);
    }

    /** A null tag throws inside Micrometer, and metrics must never be what fails a catalog write. */
    @Test
    void absentIdsAndSourcesBecomeUnknownRatherThanThrowing() {
        metrics.ruleSaved(null);
        metrics.ruleServed("  ", null);

        assertThat(registry.get("eventconductor.rule.catalog.saved")
                .tag("ruleId", "unknown").counter().count()).isEqualTo(1);
        assertThat(registry.get("eventconductor.rule.catalog.served")
                .tag("ruleId", "unknown").tag("source", "unknown").counter().count()).isEqualTo(1);
    }

    @Test
    void importedCountsRulesNotBatches() {
        metrics.rulesImported(5);
        metrics.rulesImported(2);

        assertThat(registry.get("eventconductor.rule.catalog.imported").counter().count()).isEqualTo(7);
    }
}
