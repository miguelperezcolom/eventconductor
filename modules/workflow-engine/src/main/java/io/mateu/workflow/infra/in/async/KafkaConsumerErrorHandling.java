package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.out.PoisonEventException;
import io.mateu.workflow.kafka.KafkaRedelivery;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The error handler of the engine's own Kafka consumers — {@code consumeOutbox}, {@code consumeUpstream}
 * and {@code consumeMessages} — named on each of those bindings by {@code KafkaBindingDefaults}
 * ({@code spring.cloud.stream.kafka.bindings.<binding>.consumer.common-error-handler-bean-name}).
 *
 * <p>The consumers already decide what is retryable: {@link OrchestratorKafkaConsumerConfig} parks an
 * event that will fail the same way forever and rethrows one that will not
 * ({@link io.mateu.workflow.application.services.EventFailures}), naming the record it failed on. What was
 * missing was anyone honouring the rethrow. The binder left Spring Kafka's default in place, which
 * retries a batch nine times with no pause and then discards it — so an optimistic-lock conflict
 * between two consumers writing the same process, which a parallel {@code forEach} produces all the
 * time, became a step event or a worker reply nobody would ever see again, and a process stuck for
 * good. See {@link KafkaRedelivery} for the handler itself.
 *
 * <p>Here: the retry settings ({@code workflow.consumer.retry.*}), the metric, and the recoverer. The
 * recoverer only ever sees a record the handler will not retry — one Spring Kafka classifies as fatal
 * (it could not be converted, the binding is misconfigured), or a {@link PoisonEventException} that
 * escaped — and it parks it, bytes unchanged, on the same dead-letter topic as every other
 * unprocessable event. If parking fails, the record is not committed and is retried.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@Slf4j
public class KafkaConsumerErrorHandling {

    /** The bean name the binding defaults point at. */
    public static final String ERROR_HANDLER = "eventconductorEngineErrorHandler";

    @Bean(ERROR_HANDLER)
    public CommonErrorHandler eventconductorEngineErrorHandler(
            DeadLetterPublisher deadLetterPublisher,
            WorkflowMetrics metrics,
            @Value("${workflow.consumer.retry.initial-interval:100ms}") Duration initialInterval,
            @Value("${workflow.consumer.retry.multiplier:2.0}") double multiplier,
            @Value("${workflow.consumer.retry.max-interval:10s}") Duration maxInterval,
            @Value("${workflow.consumer.retry.max-attempts:0}") long maxAttempts,
            @Value("${workflow.consumer.retry.warn-after:10}") int warnAfter) {
        var settings = new KafkaRedelivery.Settings(initialInterval, multiplier, maxInterval, maxAttempts, warnAfter);
        log.info("Engine Kafka consumers redeliver retryable failures with back-off {} x{} up to {}, {}",
                settings.initialInterval(), settings.multiplier(), settings.maxInterval(),
                settings.unlimited() ? "without limit" : "at most " + settings.maxAttempts() + " attempts");
        return KafkaRedelivery.errorHandler("engine", settings,
                (record, failure) -> park(deadLetterPublisher, record, failure),
                new KafkaRedelivery.Observer() {
                    @Override
                    public void redelivered(String topic, Throwable cause, int attempt) {
                        metrics.eventRedelivered(topic, cause.getClass().getSimpleName(), attempt);
                    }
                },
                PoisonEventException.class);
    }

    /** The record as it arrived; the dead-letter publisher counts it and says why. */
    static void park(DeadLetterPublisher deadLetterPublisher, ConsumerRecord<?, ?> record, Exception failure) {
        deadLetterPublisher.parkUnreadable(bytesOf(record.value()), KafkaRedelivery.rootCause(failure),
                record.topic());
    }

    private static byte[] bytesOf(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }
}
