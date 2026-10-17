package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.application.services.ScheduleService.InvalidScheduleException;
import io.mateu.workflow.application.services.ScheduleService.RunNowResult;
import io.mateu.workflow.application.services.ScheduleService.ScheduleDraft;
import io.mateu.workflow.application.services.ScheduleService.ScheduleNotEditableException;
import io.mateu.workflow.application.services.ScheduleService.ScheduleView;
import io.mateu.workflow.application.services.ScheduleService.UnknownScheduleException;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The schedules, over HTTP — the same {@link ScheduleService} the admin UI and the MCP tools use.
 *
 * <pre>
 *   GET    /workflow/api/schedules?workflowId=&amp;q=      every schedule (one per id)
 *   GET    /workflow/api/schedules/{id}
 *   POST   /workflow/api/schedules                       create   {id, workflowId, cron, zone?, variables?}
 *   PUT    /workflow/api/schedules/{id}                  edit     {workflowId, cron, zone?, variables?}
 *   DELETE /workflow/api/schedules/{id}
 *   POST   /workflow/api/schedules/{id}/pause
 *   POST   /workflow/api/schedules/{id}/resume
 *   POST   /workflow/api/schedules/{id}/run-now          202, the business key of the started process
 *   GET    /workflow/api/schedules/{id}/yaml             the cron.yaml entry, to promote it to git
 * </pre>
 *
 * <p>Create, edit and delete apply to the schedules created through the engine; one declared in a file
 * (a definition, a {@code cron.yaml}) answers {@code 409} — change the file. Any schedule can be paused,
 * resumed and run now. A schedule that breaks a rule answers {@code 400} with every violation in
 * {@code violations}; an unknown id {@code 404}.
 *
 * <p>Same optional {@code X-Api-Key} as the message API. Managing schedules is administration — a
 * schedule starts its workflow as the engine itself — so put this path behind whatever guards
 * {@code /_workflow-admin}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api/schedules")
@RequiredArgsConstructor
public class ScheduleRestController {

    private static final String MISSING_OR_INVALID_KEY = "Missing or invalid X-Api-Key header";

    final ScheduleService scheduleService;
    final MessageApiProperties apiProperties;
    final ObjectProvider<CallerResolver> callerResolver;

    /** The body of a create or an edit. {@code id} is only read on create. */
    public record ScheduleBody(String id, String workflowId, String cron, String zone,
                               java.util.Map<String, String> variables) {
    }

    @GetMapping
    public List<ScheduleView> list(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                   @RequestParam(value = "workflowId", required = false) String workflowId,
                                   @RequestParam(value = "q", required = false) String searchText) {
        verifyApiKey(apiKey);
        return scheduleService.list(workflowId, searchText);
    }

    @GetMapping("/{id}")
    public ScheduleView get(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                            @PathVariable("id") String id) {
        verifyApiKey(apiKey);
        return scheduleService.get(id);
    }

    @PostMapping
    public ResponseEntity<ScheduleView> create(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                               @RequestBody ScheduleBody body) {
        verifyApiKey(apiKey);
        var created = scheduleService.create(draft(body == null ? null : body.id(), body), callerContext());
        return ResponseEntity.created(URI.create("/workflow/api/schedules/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ScheduleView update(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                               @PathVariable("id") String id, @RequestBody ScheduleBody body) {
        verifyApiKey(apiKey);
        return scheduleService.update(id, draft(id, body), callerContext());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                       @PathVariable("id") String id) {
        verifyApiKey(apiKey);
        scheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public ScheduleView pause(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                              @PathVariable("id") String id) {
        verifyApiKey(apiKey);
        return scheduleService.pause(id, caller());
    }

    @PostMapping("/{id}/resume")
    public ScheduleView resume(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                               @PathVariable("id") String id) {
        verifyApiKey(apiKey);
        return scheduleService.resume(id);
    }

    /** 202: the start is requested, like an occurrence's — the process is created asynchronously. */
    @PostMapping("/{id}/run-now")
    public ResponseEntity<RunNowResult> runNow(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                               @PathVariable("id") String id) {
        verifyApiKey(apiKey);
        return ResponseEntity.accepted().body(scheduleService.runNow(id, caller(), callerContext()));
    }

    @GetMapping(value = "/{id}/yaml", produces = "application/yaml")
    public ResponseEntity<String> yaml(@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                       @PathVariable("id") String id) {
        verifyApiKey(apiKey);
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "yaml", StandardCharsets.UTF_8))
                .body(scheduleService.exportYaml(id));
    }

    @ExceptionHandler(UnknownScheduleException.class)
    ProblemDetail onUnknown(UnknownScheduleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ScheduleNotEditableException.class)
    ProblemDetail onNotEditable(ScheduleNotEditableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidScheduleException.class)
    ProblemDetail onInvalid(InvalidScheduleException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("violations", e.violations());
        return problem;
    }

    private static ScheduleDraft draft(String id, ScheduleBody body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A JSON body is required.");
        }
        return new ScheduleDraft(id, body.workflowId(), body.cron(), body.zone(), body.variables());
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
