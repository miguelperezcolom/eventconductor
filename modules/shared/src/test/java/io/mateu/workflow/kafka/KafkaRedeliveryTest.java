package io.mateu.workflow.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRedeliveryTest {

    @Test
    void byDefaultTheBackOffGrowsCapsAndNeverStops() {
        var execution = KafkaRedelivery.backOff(KafkaRedelivery.Settings.DEFAULTS).start();
        var pauses = new ArrayList<Long>();
        for (int i = 0; i < 10_000; i++) {
            pauses.add(execution.nextBackOff());
        }
        assertThat(pauses.get(0)).isEqualTo(100);
        assertThat(pauses.get(1)).isEqualTo(200);
        assertThat(pauses).doesNotContain(BackOffExecution.STOP);
        assertThat(pauses.get(pauses.size() - 1)).isEqualTo(10_000);
    }

    @Test
    void anOperatorBoundStopsAfterThatManyDeliveries() {
        var settings = new KafkaRedelivery.Settings(Duration.ofMillis(1), 2.0, Duration.ofMillis(5), 3, 10);
        var execution = KafkaRedelivery.backOff(settings).start();
        // Three deliveries in all: the first, and two retries.
        assertThat(execution.nextBackOff()).isNotEqualTo(BackOffExecution.STOP);
        assertThat(execution.nextBackOff()).isNotEqualTo(BackOffExecution.STOP);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void nonsenseSettingsAreClampedRatherThanBreakingTheConsumer() {
        var settings = new KafkaRedelivery.Settings(null, 0.1, null, -5, 0);
        assertThat(settings.initialInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(settings.maxInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.multiplier()).isEqualTo(1.0);
        assertThat(settings.unlimited()).isTrue();
        assertThat(settings.warnAfter()).isEqualTo(1);
    }

    @Test
    void everyRedeliveryIsReportedWithItsInnermostCause() {
        var seen = new ArrayList<String>();
        var listener = KafkaRedelivery.reporting("test", KafkaRedelivery.Settings.DEFAULTS, new KafkaRedelivery.Observer() {
            @Override
            public void redelivered(String topic, Throwable cause, int attempt) {
                seen.add(topic + ":" + cause.getClass().getSimpleName() + ":" + attempt);
            }
        });
        var record = new ConsumerRecord<Object, Object>("outbox", 1, 7L, null, null);
        for (int attempt = 1; attempt <= 12; attempt++) {
            listener.failedDelivery(record, new RuntimeException("wrapped", new IllegalStateException("conflict")), attempt);
        }
        assertThat(seen).hasSize(12).first().isEqualTo("outbox:IllegalStateException:1");
        assertThat(List.copyOf(seen).get(11)).isEqualTo("outbox:IllegalStateException:12");
    }
}
