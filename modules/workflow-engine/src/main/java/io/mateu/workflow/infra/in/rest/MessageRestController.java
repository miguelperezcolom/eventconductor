package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.input.InputLimits;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
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

    private static final String MISSING_OR_INVALID_KEY = "Missing or invalid X-Api-Key header";

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

    /**
     * The 400 for a body that breaks the record's contract, answered the way this endpoint answers
     * every other refusal.
     *
     * <p>Two things changed the moment {@code @Valid} took over the blank checks that used to sit
     * in the method body, and both are put back here. Spring renders a rejected body as "Invalid
     * request content." — the caller is told they got it wrong but not which field, where before
     * they were told exactly which one; the constraint's own message goes back into the detail.
     * And validation runs <em>before</em> the method does, so a caller with no key would learn
     * their body was malformed instead of being turned away: {@code verifyApiKey} is the first
     * line of {@link #sendMessage} on purpose, and checking it again here keeps the 401 in front
     * of the 400 for a request that is both.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onInvalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        if (!apiKeyAccepted(request.getHeader("X-Api-Key"))) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, MISSING_OR_INVALID_KEY);
        }
        var reason = e.getBindingResult().getFieldErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .findFirst()
                .orElse("the request body is not valid");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, reason);
    }

    private void verifyApiKey(String received) {
        if (!apiKeyAccepted(received)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MISSING_OR_INVALID_KEY);
        }
    }

    /** Whether this caller may come in: true when no key is configured, otherwise it must match. */
    private boolean apiKeyAccepted(String received) {
        String expected = messageApiProperties.getApiKey();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        // constant-time comparison to prevent timing attacks
        return received != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }
}
