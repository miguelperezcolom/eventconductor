package io.mateu.workflow.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A task whose reply the broker would not take is the worker-side way of losing a process: the task
 * ran, nobody heard, and the engine waits forever. The worker's error handler must keep it
 * uncommitted and redeliver it — however many attempts that takes.
 */
class TaskConsumerRedeliveryTest {

    @Test
    @SuppressWarnings("unchecked")
    void aRefusedReplyIsRedeliveredWithoutLimitAndCounted() {
        var registry = new SimpleMeterRegistry();
        var provider = mock(ObjectProvider.class);
        when(provider.getIfUnique(org.mockito.ArgumentMatchers.any())).thenReturn(new TaskRedeliveryMetrics(() -> registry));
        var handler = new TaskConsumerRedelivery.AutoConfig().eventconductorWorkerErrorHandler(provider,
                Duration.ZERO, 2.0, Duration.ZERO, 0, 10);
        var consumer = mock(Consumer.class);
        var container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(new ContainerProperties("downstream"));
        when(container.isRunning()).thenReturn(true);
        var record = new ConsumerRecord<Object, Object>("downstream", 2, 40L, null, new byte[0]);
        // What a worker's consumer throws when the broker refuses its reply.
        var refused = new IllegalStateException("reply not accepted", new RuntimeException("timeout"));

        for (int attempt = 1; attempt <= 30; attempt++) {
            var retried = !handler.handleOne(refused, record, consumer, container);
            assertThat(retried).as("attempt %s is retried, not committed", attempt).isTrue();
        }

        assertThat(registry.counter("eventconductor.worker.tasks.redelivered",
                "topic", "downstream", "cause", "RuntimeException").count()).isEqualTo(30);
        assertThat(registry.find("eventconductor.worker.tasks.skipped").counter()).isNull();
    }

    @Test
    void theTaskBindingsNameTheHandlerAndTurnOffTheBinderRetry() {
        var environment = new org.springframework.core.env.StandardEnvironment();
        new TaskConsumerRedelivery.Defaults().postProcessEnvironment(environment, new org.springframework.boot.SpringApplication());
        for (var binding : TaskConsumerRedelivery.TASK_BINDINGS) {
            assertThat(environment.getProperty("spring.cloud.stream.kafka.bindings." + binding
                    + ".consumer.common-error-handler-bean-name")).isEqualTo(TaskConsumerRedelivery.ERROR_HANDLER);
            assertThat(environment.getProperty("spring.cloud.stream.bindings." + binding + ".consumer.max-attempts"))
                    .isEqualTo("1");
        }
    }

    @Test
    void anApplicationsOwnSettingWins() {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("app", java.util.Map.of(
                "spring.cloud.stream.bindings.consumeWorkerEvent-in-0.consumer.max-attempts", "5")));
        new TaskConsumerRedelivery.Defaults().postProcessEnvironment(environment, new org.springframework.boot.SpringApplication());
        assertThat(environment.getProperty("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.consumer.max-attempts"))
                .isEqualTo("5");
    }
}
