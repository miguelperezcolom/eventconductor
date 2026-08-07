package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.worker.SynchronousProducerDefaults;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaProducerProperties;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the producer guarantees actually arrive at the Kafka producer, and not merely at the
 * {@code Environment}.
 *
 * <p>{@code SynchronousProducerDefaultsTest} asserts the properties are contributed; that is a
 * different claim, and on its own a dangerous one. A misspelled or wrongly-nested Spring Cloud
 * Stream property is not rejected — nothing validates the namespace — so it would sit in the
 * environment looking correct while the producer ran on the client's defaults. The failure mode is
 * silence: everything passes, nothing is pinned, and the first symptom is a reordered retry under
 * broker stress months later.
 *
 * <p>So this binds the real {@link KafkaProducerProperties} the binder uses. It needs no broker and
 * no Docker, which is the point — the property path is verified on every build rather than in the
 * distributed suite that only runs on demand.
 */
class ProducerDefaultsReachTheKafkaBinderTest {

    @Test
    void theProducerGuaranteesLandInTheBindersProducerConfiguration() {
        var environment = new MockEnvironment();
        new SynchronousProducerDefaults().postProcessEnvironment(environment, null);

        var bound = Binder.get(environment)
                .bind("spring.cloud.stream.kafka.default.producer", KafkaProducerProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "The engine's producer defaults bound to nothing: the property namespace is wrong, "
                                + "and nothing else in the build would have said so."));

        assertThat(bound.getConfiguration())
                .containsEntry("enable.idempotence", "true")
                .containsEntry("acks", "all")
                .containsEntry("max.in.flight.requests.per.connection", "5");
    }

    @Test
    void theSynchronousSendIsBoundAsWellAndNotJustPresentAsAString() {
        var environment = new MockEnvironment();
        new SynchronousProducerDefaults().postProcessEnvironment(environment, null);

        var bound = Binder.get(environment)
                .bind("spring.cloud.stream.kafka.default.producer", KafkaProducerProperties.class)
                .orElseThrow(AssertionError::new);

        assertThat(bound.isSync()).isTrue();
    }
}
