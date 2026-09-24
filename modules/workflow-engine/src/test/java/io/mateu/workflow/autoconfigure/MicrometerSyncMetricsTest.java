package io.mateu.workflow.autoconfigure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerSyncMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerWorkflowMetrics metrics = new MicrometerWorkflowMetrics(registry);

    @Test
    void countsInvocationsAnswersDeadlinesAndRejections() {
        var waiting = new AtomicInteger(3);
        metrics.syncWaitingGauge(waiting::get);
        metrics.syncInvocationStarted("wd");
        metrics.syncInvocationAnswered("wd", "REPLIED", Duration.ofMillis(12));
        metrics.syncInvocationAnswered("wd", "DEADLINE", Duration.ofMillis(5000));
        metrics.syncInvocationAnswered("wd", null, null);
        metrics.syncInvocationRejected("wd", "LOCK_BUSY");
        metrics.syncInvocationRejected("wd", null);

        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_INVOCATIONS).counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_ANSWERS).tag("outcome", "REPLIED").counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_DEADLINE_EXPIRED).counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_RESPONSE_LATENCY).tag("outcome", "REPLIED").timer().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_REJECTED).tag("reason", "LOCK_BUSY").counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_WAITING).gauge().value()).isEqualTo(3);
        waiting.set(7);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_WAITING).gauge().value()).isEqualTo(7);
    }

    @Test
    void recordsInlineDrives() {
        metrics.syncInlineSteps(4);
        metrics.syncInlineFallback("budget");
        metrics.syncInlineFallback(null);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_INLINE_STEPS).summary().totalAmount()).isEqualTo(4);
        assertThat(registry.get(MicrometerWorkflowMetrics.SYNC_INLINE_FALLBACKS).tag("reason", "budget").counter().count()).isEqualTo(1);
    }

    @Test
    void withoutARegistryEverythingIsANoOp() {
        var none = new MicrometerWorkflowMetrics(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        none.syncWaitingGauge(() -> 1);
        none.syncInvocationStarted("wd");
        none.syncInvocationAnswered("wd", "REPLIED", Duration.ZERO);
        none.syncInvocationRejected("wd", "X");
        none.syncInlineSteps(1);
        none.syncInlineFallback("x");
    }
}
