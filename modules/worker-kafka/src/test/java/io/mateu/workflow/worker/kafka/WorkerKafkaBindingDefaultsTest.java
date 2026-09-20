package io.mateu.workflow.worker.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class WorkerKafkaBindingDefaultsTest {

    private final WorkerKafkaBindingDefaults processor = new WorkerKafkaBindingDefaults();

    @Test
    void merge_adds_the_function_and_keeps_existing_ones_without_duplicates() {
        assertThat(WorkerKafkaBindingDefaults.merge("")).isEqualTo("consumeWorkerEvent");
        assertThat(WorkerKafkaBindingDefaults.merge(null)).isEqualTo("consumeWorkerEvent");
        assertThat(WorkerKafkaBindingDefaults.merge("myFn")).isEqualTo("myFn;consumeWorkerEvent");
        assertThat(WorkerKafkaBindingDefaults.merge("a;consumeWorkerEvent;b"))
                .isEqualTo("a;consumeWorkerEvent;b");
    }

    @Test
    void it_merges_the_definition_and_adds_binding_defaults() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("app",
                Map.of("spring.cloud.function.definition", "myFn")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.cloud.function.definition"))
                .isEqualTo("myFn;consumeWorkerEvent");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination"))
                .isEqualTo("downstream");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.upstream.destination"))
                .isEqualTo("upstream");
    }

    @Test
    void an_application_can_override_the_binding_defaults_but_not_drop_the_function() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("app", Map.of(
                "spring.cloud.function.definition", "myFn",
                "spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination", "orders")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        // application destination wins (added at the back)
        assertThat(environment.getProperty("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination"))
                .isEqualTo("orders");
        // but the function is still bound (added at the front)
        assertThat(environment.getProperty("spring.cloud.function.definition"))
                .isEqualTo("myFn;consumeWorkerEvent");
    }

    @Test
    void it_is_idempotent() {
        var environment = new StandardEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.cloud.function.definition"))
                .isEqualTo("consumeWorkerEvent");
    }
}
