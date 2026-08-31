package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.MessageDispatcher;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MessageRestControllerTest {

    @Mock MessageDispatcher messageDispatcher;

    MessageApiProperties properties;
    MessageRestController controller;

    /** The endpoint with the layer in between: @Valid only runs when Spring calls the method. */
    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @BeforeEach
    void setUp() {
        properties = new MessageApiProperties();
        controller = new MessageRestController(properties, messageDispatcher);
    }

    /**
     * A broker that is gone blocks the synchronous producer and then throws, and the caller used
     * to get a 500 — which tells them their request was wrong when it was not, and gives them no
     * reason to retry.
     */
    @Test
    void aBrokerThatIsDownIsAnswered503() {
        controller.mode = "kafka";
        org.mockito.Mockito.doThrow(new IllegalStateException("Timed out waiting for a node assignment"))
                .when(messageDispatcher).dispatch(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> controller.sendMessage(null,
                new SendMessageRequest("payment-received", "res-123", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * And the other half, which is the one that would hurt. In embedded mode there is no broker:
     * the publisher is the engine, running the step-over inline on this thread. A failure is a bad
     * definition or a database that said no, and calling that "the broker is offline" would be a
     * lie that hides the bug behind a status inviting a retry that will fail the same way.
     */
    @Test
    void inEmbeddedModeAFailureIsNotDressedUpAsAnOutage() {
        controller.mode = "embedded";
        org.mockito.Mockito.doThrow(new IllegalStateException("step has no form id defined"))
                .when(messageDispatcher).dispatch(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> controller.sendMessage(null,
                new SendMessageRequest("payment-received", "res-123", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("form id");
    }

    @Test
    void publishesMessageReceivedWithMappedVariables() {
        var response = controller.sendMessage(null, new SendMessageRequest(
                "payment-received", "res-123", Map.of("paymentId", "P-9")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(messageDispatcher).dispatch(new MessageReceived(
                "payment-received", "res-123", List.of(new Variable("paymentId", "P-9"))));
    }

    @Test
    void nullVariablesArePublishedAsEmptyList() {
        controller.sendMessage(null, new SendMessageRequest("payment-received", "res-123", null));

        verify(messageDispatcher).dispatch(new MessageReceived("payment-received", "res-123", List.of()));
    }

    /**
     * What correlation needs is declared on the record and enforced by {@code @Valid}, which runs
     * before the method — so this one is driven through MockMvc: called directly, the controller
     * never sees the validator and the assertion would be vacuous.
     *
     * <p>The status is the easy half. The message is the half worth pinning: Spring's own
     * rendering of a rejected body is "Invalid request content.", which tells a caller their
     * request was wrong and leaves them to guess which field, and this endpoint used to name it.
     */
    @Test
    void aBodyMissingWhatCorrelationNeedsIsRefusedAndSaysWhich() throws Exception {
        var cases = Map.of(
                "{\"messageName\": \" \", \"correlationKey\": \"res-123\"}", "messageName is required",
                "{\"messageName\": \"payment-received\"}", "correlationKey is required");

        for (var entry : cases.entrySet()) {
            mockMvc().perform(post("/workflow/api/messages")
                            .contentType(MediaType.APPLICATION_JSON).content(entry.getKey()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(entry.getValue()));
        }
        verifyNoInteractions(messageDispatcher);
    }

    /**
     * A request that is both unauthorised and malformed is answered as unauthorised. Validation
     * happens before the method body, so without the handler putting the check back in front, a
     * caller with no key would be told what is wrong with a body the endpoint should never have
     * looked at.
     */
    @Test
    void anInvalidBodyFromACallerWithNoKeyIsStillAnsweredUnauthorised() throws Exception {
        properties.setApiKey("s3cret");

        mockMvc().perform(post("/workflow/api/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageName\": \" \", \"correlationKey\": \" \"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messageDispatcher);
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
        verifyNoInteractions(messageDispatcher);
    }

    @Test
    void matchingApiKeyIsAccepted() {
        properties.setApiKey("s3cret");

        var response = controller.sendMessage("s3cret",
                new SendMessageRequest("payment-received", "res-123", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(messageDispatcher).dispatch(new MessageReceived("payment-received", "res-123", List.of()));
    }
}
