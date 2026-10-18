package io.mateu.workflow.application.httpworkers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpWorkerMetricsTest {

    @Test
    void withoutARegistryEverythingIsANoOp() {
        var metrics = HttpWorkerMetrics.none();
        metrics.enqueued("t");
        metrics.activated("t", true);
        metrics.reply("completed");
        metrics.cancelled("removed");
        metrics.rejected("overloaded");
        metrics.queueCounts(1, 2);
        metrics.waitingGauge(() -> 3);
        new HttpWorkerMetrics(null).enqueued("t");
    }

    @Test
    void theRegistryIsResolvedOnFirstUseAndTheGaugesRegistered() {
        var registry = new SimpleMeterRegistry();
        var metrics = new HttpWorkerMetrics(() -> registry);
        metrics.waitingGauge(() -> 4);
        metrics.queueCounts(5, 6);
        metrics.activated("payments", false);

        assertThat(registry.find("eventconductor.workers.http.waiting").gauge().value()).isEqualTo(4);
        assertThat(registry.find("eventconductor.workers.http.queued").tag("state", "available").gauge().value())
                .isEqualTo(5);
        assertThat(registry.find("eventconductor.workers.http.queued").tag("state", "leased").gauge().value())
                .isEqualTo(6);
        assertThat(registry.find("eventconductor.workers.http.activated").counter().count()).isEqualTo(1);
        assertThat(registry.find("eventconductor.workers.http.redelivered").counter()).isNull();
    }
}
