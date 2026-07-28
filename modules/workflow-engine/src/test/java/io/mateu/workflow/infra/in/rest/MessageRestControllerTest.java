package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.MessageRestController.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MessageRestControllerTest {

    @Mock UpstreamEventPublisher upstreamEventPublisher;

    MessageApiProperties properties;
    MessageRestController controller;

    @BeforeEach
    void setUp() {
        properties = new MessageApiProperties();
        controller = new MessageRestController(properties, upstreamEventPublisher);
    }

    @Test
    void publishesMessageReceivedWithMappedVariables() {
        var response = controller.sendMessage(null, new SendMessageRequest(
                "payment-received", "res-123", Map.of("paymentId", "P-9")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(upstreamEventPublisher).publish(new MessageReceived(
                "payment-received", "res-123", List.of(new Variable("paymentId", "P-9"))));
    }

    @Test
    void nullVariablesArePublishedAsEmptyList() {
        controller.sendMessage(null, new SendMessageRequest("payment-received", "res-123", null));

        verify(upstreamEventPublisher).publish(new MessageReceived("payment-received", "res-123", List.of()));
    }

    @Test
    void missingMessageNameIsRejected() {
        assertThatThrownBy(() -> controller.sendMessage(null, new SendMessageRequest(" ", "res-123", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(upstreamEventPublisher);
    }

    @Test
    void missingCorrelationKeyIsRejected() {
        assertThatThrownBy(() -> controller.sendMessage(null, new SendMessageRequest("payment-received", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(upstreamEventPublisher);
    }

    @Test
    void missingOrWrongApiKeyIsRejectedWhenConfigured() {
        properties.setApiKey("s3cret");

        for (String apiKey : new String[]{null, "wrong"}) {
            assertThatThrownBy(() -> controller.sendMessage(apiKey,
                    new SendMessageRequest("payment-received", "res-123", null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        verifyNoInteractions(upstreamEventPublisher);
    }

    @Test
    void matchingApiKeyIsAccepted() {
        properties.setApiKey("s3cret");

        var response = controller.sendMessage("s3cret",
                new SendMessageRequest("payment-received", "res-123", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(upstreamEventPublisher).publish(new MessageReceived("payment-received", "res-123", List.of()));
    }
}
