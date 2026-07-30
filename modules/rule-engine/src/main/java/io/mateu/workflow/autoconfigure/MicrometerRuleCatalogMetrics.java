package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * Micrometer-backed {@link RuleCatalogMetrics}. Meters are created lazily per tag
 * combination; the registry caches them, so repeated calls are cheap.
 *
 * Only instantiated by {@code RuleCatalogMetricsAutoConfiguration} when Micrometer is
 * on the classpath and a {@code MeterRegistry} bean exists — do not reference this
 * class from code that must run without Micrometer.
 */
@RequiredArgsConstructor
public class MicrometerRuleCatalogMetrics implements RuleCatalogMetrics {

    public static final String RULES_SAVED = "eventconductor.rule.catalog.saved";
    public static final String RULES_DELETED = "eventconductor.rule.catalog.deleted";
    public static final String RULES_IMPORTED = "eventconductor.rule.catalog.imported";
    public static final String RULES_SERVED = "eventconductor.rule.catalog.served";

    public static final String TAG_RULE_ID = "ruleId";
    public static final String TAG_SOURCE = "source";

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    @Override
    public void ruleSaved(String ruleId) {
        Counter.builder(RULES_SAVED)
                .description("Rules saved to the catalog")
                .tag(TAG_RULE_ID, tagValue(ruleId))
                .register(registry)
                .increment();
    }

    @Override
    public void ruleDeleted(String ruleId) {
        Counter.builder(RULES_DELETED)
                .description("Rules deleted from the catalog")
                .tag(TAG_RULE_ID, tagValue(ruleId))
                .register(registry)
                .increment();
    }

    @Override
    public void rulesImported(long count) {
        Counter.builder(RULES_IMPORTED)
                .description("Rules imported into the catalog")
                .register(registry)
                .increment(count);
    }

    @Override
    public void ruleServed(String ruleId, String source) {
        Counter.builder(RULES_SERVED)
                .description("Rules served from the catalog, by source")
                .tag(TAG_RULE_ID, tagValue(ruleId))
                .tag(TAG_SOURCE, tagValue(source))
                .register(registry)
                .increment();
    }

    private static String tagValue(String value) {
        return value != null && !value.isBlank() ? value : UNKNOWN;
    }
}
