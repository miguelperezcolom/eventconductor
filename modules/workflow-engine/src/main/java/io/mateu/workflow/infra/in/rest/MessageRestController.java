package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.input.InputLimits;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * REST entry point for external systems that cannot produce to Kafka (webhooks, SaaS
 * callbacks): POST /workflow/api/messages publishes a {@link MessageReceived} through the
 * same upstream surface as the Kafka topic, the embedded publisher and the sendMessage MCP
 * tool, so MESSAGE steps correlate exactly the same whatever the channel.
 *
 * Responds 202: delivery is fire-and-forget — a message that matches no waiting step is
 * ignored by design (not buffered; the sender retries), so matching cannot be confirmed here.
 *
 * Configure in application.yml:
 * <pre>
 *   workflow:
 *     message-api:
 *       api-key: mysecret   # optional — when set, the X-Api-Key header is required
 * </pre>
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api/messages")
@RequiredArgsConstructor
@Slf4j
public class MessageRestController {

    final MessageApiProperties messageApiProperties;
    final io.mateu.workflow.application.services.MessageDispatcher messageDispatcher;

    /** Whether there is a broker at all — see the dispatch below. */
    @org.springframework.beans.factory.annotation.Value("${workflow.mode:embedded}")
    String mode;

    public record SendMessageRequest(
            @NotBlank(message = "messageName is required")
            String messageName,
            @NotBlank(message = "correlationKey is required")
            String correlationKey,
            Map<String, String> variables) {
    }

    @PostMapping
    public ResponseEntity<String> sendMessage(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @Valid @RequestBody SendMessageRequest request) {

        verifyApiKey(apiKey);

        if (request.messageName() == null || request.messageName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageName is required");
        }
        if (request.correlationKey() == null || request.correlationKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "correlationKey is required");
        }

        var variables = request.variables() == null ? List.<Variable>of() : request.variables().entrySet().stream()
                .map(entry -> new Variable(entry.getKey(), entry.getValue()))
                .toList();
        // The same limits the upstream chokepoint enforces, run here as well and on purpose. Left to
        // the chokepoint alone, an oversized body would be answered 202 and then quietly dead-lettered
        // on the far side of the broker, which tells the caller nothing: this is the one channel that
        // can still say no to its face.
        refuseIfOversized(request, variables);
        log.info("REST message '{}' received with correlation key '{}'", request.messageName(), request.correlationKey());
        try {
            messageDispatcher.dispatch(new MessageReceived(request.messageName(), request.correlationKey(), variables));
        } catch (ResponseStatusException e) {
            // Something deeper already decided what to answer. Leave it alone.
            throw e;
        } catch (RuntimeException e) {
            // Only kafka mode has a broker to be down. In embedded mode the publisher IS the
            // engine — EmbeddedUpstreamEventPublisher runs the step-over inline on this thread —
            // so a failure here is a bad definition, a guard that would not evaluate, a database
            // that said no. Answering 503 "the broker is offline" would be a lie about a system
            // that has no broker, and it would hide the bug behind a status that invites a retry.
            if (!"kafka".equals(mode)) {
                throw e;
            }
            // In kafka mode this call does one thing: hand the record to the binder. The producer
            // is synchronous (see PartitionedEvents), so a broker that is gone blocks and then
            // throws here rather than returning a false the caller would never hear about. 503
            // says what is true — try again later — where 500 tells the caller their request was
            // wrong when it was not.
            log.error("Could not publish REST message '{}' to the broker", request.messageName(), e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Messaging broker is temporarily unavailable. Please retry.",
                    e);
        }

        return ResponseEntity.accepted().body("message published");
    }

    private void refuseIfOversized(SendMessageRequest request, List<Variable> variables) {
        try {
            InputLimits.checkIdentifier(request.messageName(), "messageName");
            InputLimits.checkIdentifier(request.correlationKey(), "correlationKey");
            InputLimits.checkVariables(variables, "message '" + request.messageName() + "'");
        } catch (InputLimits.InputRejectedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void verifyApiKey(String received) {
        String expected = messageApiProperties.getApiKey();
        if (expected == null || expected.isBlank()) {
            return;
        }
        // constant-time comparison to prevent timing attacks
        if (received == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Api-Key header");
        }
    }
}
