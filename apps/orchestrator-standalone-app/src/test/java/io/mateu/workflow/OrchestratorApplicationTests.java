package io.mateu.workflow;

import io.mateu.workflow.events.kafka.InboundEventKafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrchestratorApplicationTests {

    @Autowired ApplicationContext context;
    @Autowired org.springframework.core.env.Environment environment;

    @Test
    void contextLoads() {
    }

    /** Kafka mode: the inbound domain-event consumer, now its own module, is still wired in. */
    @Test
    void theInboundEventConsumerIsWiredInKafkaMode() {
        assertThat(context.getBeansOfType(InboundEventKafkaConsumer.class)).hasSize(1);
    }

    /**
     * The built-in workers ship in this image: HTTP_CALL, LLM_CALL and PUBLISH_EVENT are served by the
     * orchestrator itself, on their own topics, with no worker deployment.
     */
    @Test
    void theBuiltInWorkersServeTheirTasksOnTheirTopics() {
        var registry = context.getBean(io.mateu.workflow.worker.api.TaskRegistry.class);
        assertThat(registry.resolve("http-call@1", "s")).isNotNull();
        assertThat(registry.resolve("llm-call@1", "s")).isNotNull();
        assertThat(registry.resolve("publish-event@1", "s")).isNotNull();
        assertThat(environment.getProperty("spring.cloud.function.definition")).contains("consumeWorkerEvent");
        assertThat(environment.getProperty("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination"))
                .isEqualTo("http-calls,llm-calls,event-publications");
    }
}
