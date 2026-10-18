package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.aggregates.ProcessRetention;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which retention a finished process gets: the definition's, then the per-status default, then the default. */
class ProcessRetentionPolicyTest {

    private static WorkflowDefinition definition(ProcessRetention retention) {
        return new WorkflowDefinition("wf", "Wf", 1, null, false, 0, false, null, 0, List.of()).withRetention(retention);
    }

    @Test
    void offUnlessSomethingSaysOtherwise() {
        var policy = ProcessRetentionPolicy.of(new ProcessRetentionProperties());
        assertThat(policy.hasDefaults()).isFalse();
        for (var status : ProcessStatus.values()) {
            assertThat(policy.retentionFor(definition(null), status)).isEmpty();
            assertThat(policy.retentionFor(null, status)).isEmpty();
        }
        assertThat(policy.describe(definition(null))).isEqualTo("kept forever");
    }

    @Test
    void theDefinitionWinsThenThePerStatusDefaultThenTheDefault() {
        var properties = new ProcessRetentionProperties();
        properties.setRetention(Duration.ofDays(30));
        properties.setRetentionByStatus(Map.of("compensation-failed", Duration.ofDays(365), "ERROR", Duration.ofDays(180)));
        var policy = ProcessRetentionPolicy.of(properties);
        assertThat(policy.hasDefaults()).isTrue();

        // Nothing declared: the engine defaults, per status first.
        var plain = definition(null);
        assertThat(policy.retentionFor(plain, ProcessStatus.COMPLETED)).contains(Duration.ofDays(30));
        assertThat(policy.retentionFor(plain, ProcessStatus.ERROR)).contains(Duration.ofDays(180));
        assertThat(policy.retentionFor(plain, ProcessStatus.COMPENSATION_FAILED)).contains(Duration.ofDays(365));
        // A deleted definition is judged by the defaults too.
        assertThat(policy.retentionFor(null, ProcessStatus.CANCELLED)).contains(Duration.ofDays(30));

        // One value for every status beats every default.
        var single = definition(ProcessRetention.of("P7D"));
        for (var status : ProcessRetention.TERMINAL) {
            assertThat(policy.retentionFor(single, status)).contains(Duration.ofDays(7));
        }
        assertThat(policy.describe(single)).isEqualTo("P7D");

        // Per status: what it lists wins, what it does not falls back; forever keeps whatever the defaults say.
        var perStatus = definition(ProcessRetention.perStatus(Map.of("completed", "PT12H", "error", "forever")));
        assertThat(policy.retentionFor(perStatus, ProcessStatus.COMPLETED)).contains(Duration.ofHours(12));
        assertThat(policy.retentionFor(perStatus, ProcessStatus.ERROR)).isEmpty();
        assertThat(policy.retentionFor(perStatus, ProcessStatus.CANCELLED)).contains(Duration.ofDays(30));
        assertThat(policy.retentionFor(perStatus, ProcessStatus.COMPENSATION_FAILED)).contains(Duration.ofDays(365));
        assertThat(policy.describe(perStatus)).isEqualTo("completed PT12H · cancelled P30D (engine default) · "
                + "error kept forever · compensated P30D (engine default) · compensationFailed P365D (engine default)");
    }

    @Test
    void neverForAProcessThatHasNotEnded() {
        var properties = new ProcessRetentionProperties();
        properties.setRetention(Duration.ofSeconds(1));
        var policy = ProcessRetentionPolicy.of(properties);
        var single = definition(ProcessRetention.of("PT1S"));
        for (var status : List.of(ProcessStatus.PENDING, ProcessStatus.RUNNING, ProcessStatus.PAUSED)) {
            assertThat(policy.retentionFor(single, status)).isEmpty();
            assertThat(policy.retentionFor(null, status)).isEmpty();
        }
    }

    @Test
    void aMisconfiguredDefaultFailsAtStartup() {
        var badKey = new ProcessRetentionProperties();
        badKey.setRetentionByStatus(Map.of("running", Duration.ofDays(1)));
        assertThatThrownBy(() -> ProcessRetentionPolicy.of(badKey)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("running");
        var zero = new ProcessRetentionProperties();
        zero.setRetention(Duration.ZERO);
        assertThat(ProcessRetentionPolicy.iso(Duration.ofHours(36))).isEqualTo("P1DT12H");
        assertThat(ProcessRetentionPolicy.iso(Duration.ofMinutes(90))).isEqualTo("PT1H30M");
        assertThatThrownBy(() -> ProcessRetentionPolicy.of(zero)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longer than zero");
    }
}
