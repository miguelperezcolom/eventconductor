package io.mateu.workflow.dtos.events.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A message crosses Kafka as JSON: the event origin must survive it, and a plain message must not grow it. */
class MessageReceivedJsonTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theOriginOfAMessageMadeFromAnEventSurvivesTheWire() throws Exception {
        var message = new MessageReceived("paid", "bk-1", List.of(new Variable("a", "1")),
                new MessageReceived.EventOrigin("payments", "com.acme.paid", "psp", "evt-1"));

        var json = mapper.writeValueAsString(message);

        assertThat(mapper.readValue(json, DomainEvent.class)).isEqualTo(message);
    }

    @Test
    void aPlainMessageIsWrittenAsBeforeAndReadWithoutAnOrigin() throws Exception {
        var json = mapper.writeValueAsString(new MessageReceived("paid", "bk-1", List.of()));

        assertThat(json).doesNotContain("origin");
        assertThat(mapper.readValue("{\"type\":\"message-received\",\"messageName\":\"paid\",\"correlationKey\":\"bk-1\","
                + "\"variables\":[],\"somethingNewer\":1}", DomainEvent.class))
                .isEqualTo(new MessageReceived("paid", "bk-1", List.of()));
    }
}
