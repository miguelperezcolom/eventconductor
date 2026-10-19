package io.mateu.workflow.kafka;

import java.util.function.Supplier;

/**
 * Counts a task consumer's redeliveries in Micrometer. Created only when Micrometer is on the
 * classpath ({@link TaskConsumerRedelivery.AutoConfig}); the registry is looked up lazily, so a context
 * without one simply counts nothing.
 */
public class TaskRedeliveryMetrics implements KafkaRedelivery.Observer {

    public static final String REDELIVERED = "eventconductor.worker.tasks.redelivered";
    public static final String SKIPPED = "eventconductor.worker.tasks.skipped";

    private final Supplier<?> registry;

    public TaskRedeliveryMetrics(Supplier<?> registry) {
        this.registry = registry;
    }

    @Override
    public void redelivered(String topic, Throwable cause, int attempt) {
        count(REDELIVERED, topic, cause);
    }

    @Override
    public void recovered(String topic, Throwable cause) {
        count(SKIPPED, topic, cause);
    }

    private void count(String name, String topic, Throwable cause) {
        if (registry.get() instanceof io.micrometer.core.instrument.MeterRegistry meters) {
            meters.counter(name, "topic", String.valueOf(topic), "cause", cause.getClass().getSimpleName())
                    .increment();
        }
    }
}
