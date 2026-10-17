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

    @Test
    void contextLoads() {
    }

    /** Kafka mode: the inbound domain-event consumer, now its own module, is still wired in. */
    @Test
    void theInboundEventConsumerIsWiredInKafkaMode() {
        assertThat(context.getBeansOfType(InboundEventKafkaConsumer.class)).hasSize(1);
    }
}
