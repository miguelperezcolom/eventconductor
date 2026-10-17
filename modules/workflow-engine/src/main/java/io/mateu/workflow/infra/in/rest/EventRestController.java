package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.events.InboundCloudEvents;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Domain events in, over HTTP — for tests and for systems that cannot publish to Kafka — and the event
 * triggers, read and operated: the same {@link InboundEventService} the Kafka consumer feeds and the
 * same {@link EventTriggerService} the admin UI and the MCP tools use.
 *
 * <pre>
 *   POST /workflow/api/events/{destination}?key=      a structured CloudEvent; 200 with what it did
 *   GET  /workflow/api/events/received?workflowId=&amp;limit=   the latest receipts, newest first
 *   GET  /workflow/api/triggers?workflowId=&amp;q=          every trigger
 *   GET  /workflow/api/triggers/{workflowId}/{triggerId}
 *   POST /workflow/api/triggers/{workflowId}/{triggerId}/pause
 *   POST /workflow/api/triggers/{workflowId}/{triggerId}/resume
 * </pre>
 *
 * <p>The inject body is a CloudEvent in structured JSON: {@code {"specversion": "1.0", "id": "…",
 * "source": "…", "type": "…", "subject": "…", "data": {…}}}, plus an optional {@code key} (or the
 * {@code key} parameter) standing for the Kafka record key. {@code id} and {@code source} are required —
 * they are what makes a second post of the same event a duplicate. The event is received on this
 * request in both modes, so the answer says what it started and delivered.
 *
 * <p>Same optional {@code X-Api-Key} as the message API. An injected event starts workflows as the
 * engine itself, so put this path behind whatever guards the message API.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api")
@RequiredArgsConstructor
@Slf4j
public class EventRestController {

    private static final String MISSING_OR_INVALID_KEY = "Missing or invalid X-Api-Key header";

    final InboundEventService inboundEventService;
    final EventTriggerService eventTriggerService;
    final MessageApiProperties apiProperties;
    final ObjectProvider<CallerResolver> callerResolver;

    @PostMapping("/events/{destination}")
    public InboundEventService.Result publish(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                              @PathVariable("destination") String destination,
                                              @RequestParam(value = "key", required = false) String key,
                                              @RequestBody Map<String, Object> cloudEvent) {
        verifyApiKey(apiKey);
        if (cloudEvent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A CloudEvent in structured JSON is required.");
        }
        var event = InboundCloudEvents.fromStructured(destination, cloudEvent, key);
        try {
            InputLimits.checkIdentifier(destination, "destination");
            InputLimits.checkIdentifier(event.id(), "id");
            InputLimits.checkIdentifier(event.type(), "type");
        } catch (InputLimits.InputRejectedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        log.info("REST domain event {} ({}) received on '{}'", event.id(), event.type(), destination);
        return inboundEventService.receive(event);
    }

    @GetMapping("/events/received")
    public List<EventReceipt> received(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                       @RequestParam(value = "workflowId", required = false) String workflowId,
                                       @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        verifyApiKey(apiKey);
        return eventTriggerService.received(limit, workflowId);
    }

    @GetMapping("/triggers")
    public List<TriggerView> triggers(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                      @RequestParam(value = "workflowId", required = false) String workflowId,
                                      @RequestParam(value = "q", required = false) String searchText) {
        verifyApiKey(apiKey);
        return eventTriggerService.list(workflowId, searchText);
    }

    @GetMapping("/triggers/{workflowId}/{triggerId}")
    public TriggerView trigger(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                               @PathVariable("workflowId") String workflowId, @PathVariable("triggerId") String triggerId) {
        verifyApiKey(apiKey);
        return eventTriggerService.get(workflowId, triggerId);
    }

    /** The body of a create or an edit. {@code id} and {@code workflowId} are only read on create. */
    public record TriggerBody(String id, String workflowId, String destination, String type, String filter,
                              String businessKey, Map<String, Object> variables) {
    }

    @PostMapping("/triggers")
    public org.springframework.http.ResponseEntity<TriggerView> createTrigger(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey, @RequestBody TriggerBody body) {
        verifyApiKey(apiKey);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A JSON body is required.");
        }
        var created = eventTriggerService.create(new EventTriggerService.TriggerDraft(body.id(), body.workflowId(),
                body.destination(), body.type(), body.filter(), body.businessKey(), body.variables()), callerContext());
        return org.springframework.http.ResponseEntity
                .created(java.net.URI.create("/workflow/api/triggers/" + created.workflowId() + "/" + created.triggerId()))
                .body(created);
    }

    @org.springframework.web.bind.annotation.PutMapping("/triggers/{workflowId}/{triggerId}")
    public TriggerView updateTrigger(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                     @PathVariable("workflowId") String workflowId, @PathVariable("triggerId") String triggerId,
                                     @RequestBody TriggerBody body) {
        verifyApiKey(apiKey);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A JSON body is required.");
        }
        return eventTriggerService.update(workflowId, triggerId, new EventTriggerService.TriggerDraft(triggerId,
                body.workflowId() == null ? workflowId : body.workflowId(), body.destination(), body.type(), body.filter(),
                body.businessKey(), body.variables()), callerContext());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/triggers/{workflowId}/{triggerId}")
    public org.springframework.http.ResponseEntity<Void> deleteTrigger(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @PathVariable("workflowId") String workflowId, @PathVariable("triggerId") String triggerId) {
        verifyApiKey(apiKey);
        eventTriggerService.delete(workflowId, triggerId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/triggers/{workflowId}/{triggerId}/yaml", produces = "application/yaml")
    public org.springframework.http.ResponseEntity<String> triggerYaml(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @PathVariable("workflowId") String workflowId, @PathVariable("triggerId") String triggerId) {
        verifyApiKey(apiKey);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(new org.springframework.http.MediaType("application", "yaml", java.nio.charset.StandardCharsets.UTF_8))
                .body(eventTriggerService.exportYaml(workflowId, triggerId));
    }

    @PostMapping("/triggers/{workflowId}/{triggerId}/pause")
    public TriggerView pause(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                             @PathVariable("workflowId") String workflowId, @PathVariable("triggerId") String triggerId) {
        verifyApiKey(apiKey);
        return eventTriggerService.pause(workflowId, triggerId, caller());
    }

    @PostMapping("/triggers/{workflowId}/{triggerId}/resume")
    public TriggerView resume(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                              @PathVariable("workflowId") String workflowId, @PathVariable("triggerId") String triggerId) {
        verifyApiKey(apiKey);
        return eventTriggerService.resume(workflowId, triggerId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail onUnknown(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onInvalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(EventTriggerService.TriggerNotEditableException.class)
    ProblemDetail onNotEditable(EventTriggerService.TriggerNotEditableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(EventTriggerService.InvalidTriggerException.class)
    ProblemDetail onInvalidTrigger(EventTriggerService.InvalidTriggerException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("violations", e.violations());
        return problem;
    }

    /** The caller as resolved at this request, for the flow-authorization check. */
    private io.mateu.workflow.security.AuthorizationContext callerContext() {
        var resolver = callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    /** A caller who may not start the workflow: 403, as a refused process creation. */
    @ExceptionHandler(io.mateu.workflow.security.FlowAuthorizationDeniedException.class)
    ProblemDetail onDenied(io.mateu.workflow.security.FlowAuthorizationDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private String caller() {
        var resolver = callerResolver.getIfAvailable();
        var context = resolver == null ? null : resolver.current();
        return context == null ? "api" : context.subject();
    }

    private void verifyApiKey(String received) {
        String expected = apiProperties.getApiKey();
        if (expected == null || expected.isBlank()) {
            return;
        }
        // constant-time comparison to prevent timing attacks
        if (received == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MISSING_OR_INVALID_KEY);
        }
    }
}
