package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.infra.config.MessageApiProperties;
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
    final UpstreamEventPublisher upstreamEventPublisher;

    public record SendMessageRequest(String messageName, String correlationKey, Map<String, String> variables) {
    }

    @PostMapping
    public ResponseEntity<String> sendMessage(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody SendMessageRequest request) {

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
        log.info("REST message '{}' received with correlation key '{}'", request.messageName(), request.correlationKey());
        upstreamEventPublisher.publish(new MessageReceived(request.messageName(), request.correlationKey(), variables));

        return ResponseEntity.accepted().body("message published");
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
