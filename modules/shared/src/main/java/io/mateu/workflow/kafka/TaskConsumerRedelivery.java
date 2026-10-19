package io.mateu.workflow.kafka;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.util.ClassUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redelivery for every consumer that answers the engine's tasks over Kafka — the {@code worker-kafka}
 * consumer, the rule runtime answering RULE steps, the forms engine answering USER_TASKs, and the
 * workers built on them ({@code worker-standalone-app}, the HTTP and LLM workers).
 *
 * <p>What reaches a task consumer's error handler is a reply the broker would not take: a handler's
 * own failure is turned into a FAILED reply, and the reply is published before the consumer returns.
 * Left to the binder, that record was retried {@code max-attempts} times (three by default) in memory
 * and then committed — the task done, its answer lost, and its process waiting forever. Here it is
 * redelivered with exponential back-off and no limit (see {@link KafkaRedelivery}), until the broker
 * takes the reply; only a record Spring Kafka classifies as unreadable is skipped, logged and counted.
 *
 * <p>Two halves that must agree: the {@link Defaults} name the handler on the task bindings, and the
 * {@link AutoConfig} provides it. Both apply exactly when Spring Kafka is on the classpath, so a
 * binding never names a bean that does not exist. Both stand aside for an application's own choice.
 * Settings under {@code eventconductor.worker.consumer.retry.*}.
 */
public final class TaskConsumerRedelivery {

    /** The bean the task bindings name. */
    public static final String ERROR_HANDLER = "eventconductorWorkerErrorHandler";

    /** The bindings of the consumers that answer tasks. */
    public static final List<String> TASK_BINDINGS = List.of(
            "consumeWorkerEvent-in-0",
            "consumeWorkerEventForRuleRuntime-in-0",
            "consumeWorkerEventForFormsEngine-in-0");

    static final String KAFKA_CLASS = "org.springframework.kafka.listener.DefaultErrorHandler";

    private TaskConsumerRedelivery() {
    }

    /** Names {@link #ERROR_HANDLER} on the task bindings, at the lowest precedence. */
    public static class Defaults implements EnvironmentPostProcessor, Ordered {

        private static final String NAME = "eventconductor-task-consumer-redelivery-defaults";

        @Override
        public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
            var sources = environment.getPropertySources();
            if (sources.contains(NAME) || !ClassUtils.isPresent(KAFKA_CLASS, Defaults.class.getClassLoader())) {
                return;
            }
            var defaults = new LinkedHashMap<String, Object>();
            for (var binding : TASK_BINDINGS) {
                defaults.put("spring.cloud.stream.kafka.bindings." + binding
                        + ".consumer.common-error-handler-bean-name", ERROR_HANDLER);
                // No binder in-memory retry in front of it: that one ends in a commit.
                defaults.put("spring.cloud.stream.bindings." + binding + ".consumer.max-attempts", "1");
            }
            sources.addLast(new MapPropertySource(NAME, Map.copyOf(defaults)));
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /** Provides {@link #ERROR_HANDLER}, and its counters when Micrometer is there. */
    @AutoConfiguration
    @ConditionalOnClass(name = KAFKA_CLASS)
    public static class AutoConfig {

        @Bean(ERROR_HANDLER)
        @ConditionalOnMissingBean(name = ERROR_HANDLER)
        public CommonErrorHandler eventconductorWorkerErrorHandler(
                ObjectProvider<KafkaRedelivery.Observer> observers,
                @Value("${eventconductor.worker.consumer.retry.initial-interval:100ms}") Duration initialInterval,
                @Value("${eventconductor.worker.consumer.retry.multiplier:2.0}") double multiplier,
                @Value("${eventconductor.worker.consumer.retry.max-interval:10s}") Duration maxInterval,
                @Value("${eventconductor.worker.consumer.retry.max-attempts:0}") long maxAttempts,
                @Value("${eventconductor.worker.consumer.retry.warn-after:10}") int warnAfter) {
            return KafkaRedelivery.errorHandler("task consumer",
                    new KafkaRedelivery.Settings(initialInterval, multiplier, maxInterval, maxAttempts, warnAfter),
                    null, observers.getIfUnique(() -> KafkaRedelivery.Observer.NONE));
        }

        /**
         * {@code eventconductor.worker.tasks.redelivered} (a task redelivered because its reply was
         * refused) and {@code eventconductor.worker.tasks.skipped} (a record that could not be read).
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        static class Metrics {

            @Bean
            @ConditionalOnMissingBean(KafkaRedelivery.Observer.class)
            KafkaRedelivery.Observer eventconductorWorkerRedeliveryMetrics(
                    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
                return new TaskRedeliveryMetrics(registry::getIfAvailable);
            }
        }
    }
}
