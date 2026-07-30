package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.RuleRuntimeMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Micrometer-backed {@link RuleRuntimeMetrics}. Meters are created lazily per tag
 * combination; the registry caches them, so repeated calls are cheap.
 *
 * Only instantiated by {@code RuleRuntimeMetricsAutoConfiguration} when Micrometer
 * is on the classpath and a {@code MeterRegistry} bean exists — do not reference
 * this class from code that must run without Micrometer.
 */
public class MicrometerRuleRuntimeMetrics implements RuleRuntimeMetrics {

    public static final String RULE_EVALUATIONS = "eventconductor.rule.evaluation.count";
    public static final String RULE_DURATION = "eventconductor.rule.evaluation.duration";
    public static final String RULE_CACHE = "eventconductor.rule.evaluation.cache";

    public static final String TAG_RULE_ID = "ruleId";
    public static final String TAG_RULE_TYPE = "ruleType";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_RESULT = "result";

    private static final String UNKNOWN = "unknown";
    private static final String RESULT_HIT = "hit";
    private static final String RESULT_MISS = "miss";

    private final MeterRegistry registry;

    public MicrometerRuleRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void ruleEvaluated(String ruleId, String ruleType, String outcome, Duration duration) {
        Counter.builder(RULE_EVALUATIONS)
                .description("Rule evaluations, by outcome")
                .tag(TAG_RULE_ID, tagValue(ruleId))
                .tag(TAG_RULE_TYPE, tagValue(ruleType))
                .tag(TAG_OUTCOME, tagValue(outcome))
                .register(registry)
                .increment();
        if (duration != null && !duration.isNegative()) {
            Timer.builder(RULE_DURATION)
                    .description("Rule evaluation duration")
                    .tag(TAG_RULE_ID, tagValue(ruleId))
                    .tag(TAG_RULE_TYPE, tagValue(ruleType))
                    .register(registry)
                    .record(duration);
        }
    }

    @Override
    public void cacheHit(String ruleId) {
        cache(ruleId, RESULT_HIT);
    }

    @Override
    public void cacheMiss(String ruleId) {
        cache(ruleId, RESULT_MISS);
    }

    private void cache(String ruleId, String result) {
        Counter.builder(RULE_CACHE)
                .description("Rule source cache lookups, by result")
                .tag(TAG_RULE_ID, tagValue(ruleId))
                .tag(TAG_RESULT, result)
                .register(registry)
                .increment();
    }

    private static String tagValue(String value) {
        return value != null && !value.isBlank() ? value : UNKNOWN;
    }
}
