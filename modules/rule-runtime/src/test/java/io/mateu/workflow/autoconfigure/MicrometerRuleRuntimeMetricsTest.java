package io.mateu.workflow.autoconfigure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule-runtime meters, which nothing exercised.
 *
 * <p>Names are asserted as literals, not through the constants: meter names are part of the 1.0
 * contract and their consumers — dashboards, alert rules — live outside this build, so a rename has
 * to fail here or it fails in production silently.
 */
class MicrometerRuleRuntimeMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerRuleRuntimeMetrics metrics = new MicrometerRuleRuntimeMetrics(registry);

    @Test
    void theMeterNamesAndTagsAreTheOnesTheDocumentationPromises() {
        metrics.ruleEvaluated("rule-1", "decision", "matched", Duration.ofMillis(5));
        metrics.cacheHit("rule-1");

        assertThat(registry.get("eventconductor.rule.evaluation.count")
                .tag("ruleId", "rule-1").tag("ruleType", "decision").tag("outcome", "matched")
                .counter()).isNotNull();
        assertThat(registry.get("eventconductor.rule.evaluation.duration")
                .tag("ruleId", "rule-1").tag("ruleType", "decision").timer()).isNotNull();
        assertThat(registry.get("eventconductor.rule.evaluation.cache")
                .tag("ruleId", "rule-1").tag("result", "hit").counter()).isNotNull();
    }

    /**
     * The three outcomes the documentation names. `error` in particular has to be its own outcome
     * rather than an absence: a rule that throws every time otherwise looks like a rule nobody is
     * calling.
     */
    @Test
    void evaluationsAreSplitByOutcome() {
        metrics.ruleEvaluated("rule-1", "decision", "matched", Duration.ofMillis(1));
        metrics.ruleEvaluated("rule-1", "decision", "nomatch", Duration.ofMillis(1));
        metrics.ruleEvaluated("rule-1", "decision", "error", Duration.ofMillis(1));
        metrics.ruleEvaluated("rule-1", "decision", "matched", Duration.ofMillis(1));

        assertThat(registry.get("eventconductor.rule.evaluation.count")
                .tag("outcome", "matched").counter().count()).isEqualTo(2);
        assertThat(registry.get("eventconductor.rule.evaluation.count")
                .tag("outcome", "nomatch").counter().count()).isEqualTo(1);
        assertThat(registry.get("eventconductor.rule.evaluation.count")
                .tag("outcome", "error").counter().count()).isEqualTo(1);
    }

    /**
     * The duration timer deliberately carries no outcome tag: an evaluation that matched and one
     * that did not cost the same to run, and splitting them would halve the samples behind every
     * latency percentile for no gain.
     */
    @Test
    void durationIsTimedPerRuleAndTypeAcrossOutcomes() {
        metrics.ruleEvaluated("rule-1", "decision", "matched", Duration.ofSeconds(1));
        metrics.ruleEvaluated("rule-1", "decision", "nomatch", Duration.ofSeconds(2));

        var timer = registry.get("eventconductor.rule.evaluation.duration")
                .tag("ruleId", "rule-1").tag("ruleType", "decision").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(3);
    }

    @Test
    void aMissingOrNegativeDurationStillCountsTheEvaluationButRecordsNoTime() {
        metrics.ruleEvaluated("rule-1", "decision", "matched", null);
        metrics.ruleEvaluated("rule-1", "decision", "matched", Duration.ofMillis(-1));

        assertThat(registry.get("eventconductor.rule.evaluation.count").counter().count()).isEqualTo(2);
        assertThat(registry.find("eventconductor.rule.evaluation.duration").timer()).isNull();
    }

    @Test
    void cacheHitsAndMissesShareOneCounterSplitByResult() {
        metrics.cacheHit("rule-1");
        metrics.cacheHit("rule-1");
        metrics.cacheMiss("rule-1");

        assertThat(registry.get("eventconductor.rule.evaluation.cache")
                .tag("result", "hit").counter().count()).isEqualTo(2);
        assertThat(registry.get("eventconductor.rule.evaluation.cache")
                .tag("result", "miss").counter().count()).isEqualTo(1);
    }

    /** A null tag throws inside Micrometer, and metrics must never be what fails an evaluation. */
    @Test
    void absentIdsTypesAndOutcomesBecomeUnknownRatherThanThrowing() {
        metrics.ruleEvaluated(null, "  ", null, Duration.ofMillis(1));
        metrics.cacheMiss(null);

        assertThat(registry.get("eventconductor.rule.evaluation.count")
                .tag("ruleId", "unknown").tag("ruleType", "unknown").tag("outcome", "unknown")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("eventconductor.rule.evaluation.cache")
                .tag("ruleId", "unknown").tag("result", "miss").counter().count()).isEqualTo(1);
    }
}
