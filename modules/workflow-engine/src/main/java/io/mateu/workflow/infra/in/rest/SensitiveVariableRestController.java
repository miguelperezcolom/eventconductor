package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.ProcessOperationsService.ProcessNotFoundException;
import io.mateu.workflow.application.services.SensitiveVariableRevealService;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /workflow/api/processes/{id}/variables/{name}/reveal {reason}}: the value of one sensitive
 * variable, in clear — for a caller holding one of {@code workflow.security.sensitive.reveal-roles},
 * audited in the process's log (see {@link SensitiveVariableRevealService}). Every other read of a process
 * shows a sensitive value as {@code ••••}.
 *
 * <p>Same access rule as the rest of {@code /workflow/api/**} ({@link EngineApiAccess}); the reveal role is
 * required on top of it, so an API key alone (no identified caller) can never reveal.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api/processes")
@Tag(name = "Processes", description = "Start, read, list and operate processes.")
public class SensitiveVariableRestController {

    private final SensitiveVariableRevealService reveal;
    private final MessageApiProperties apiProperties;
    private final ObjectProvider<CallerResolver> callerResolver;

    public SensitiveVariableRestController(SensitiveVariableRevealService reveal, MessageApiProperties apiProperties,
                                           ObjectProvider<CallerResolver> callerResolver) {
        this.reveal = reveal;
        this.apiProperties = apiProperties;
        this.callerResolver = callerResolver;
    }

    @PostMapping("/{id}/variables/{name}/reveal")
    @Operation(operationId = "revealProcessVariable", summary = "Reveal a sensitive variable",
            description = "The value of one of the process's sensitive variables, in clear. Needs one of"
                    + " workflow.security.sensitive.reveal-roles and a reason; every reveal (and every refusal) is"
                    + " recorded in the process's audit trail.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The value."),
            @ApiResponse(responseCode = "400", description = "No reason, or the variable is not sensitive.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "The caller holds no reveal role, or may not read the process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such process or variable.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ProcessApi.RevealedVariable reveal(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @PathVariable("id") String id,
            @PathVariable("name") String name,
            @RequestBody(required = false) ProcessApi.RevealRequest body) {
        EngineApiAccess.verify(apiProperties, apiKey);
        var value = reveal.reveal(id, name, body == null ? null : body.reason(), caller());
        return new ProcessApi.RevealedVariable(id, name, value);
    }

    private AuthorizationContext caller() {
        var resolver = callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    @ExceptionHandler(ProcessNotFoundException.class)
    ProblemDetail onNotFound(ProcessNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(FlowAuthorizationDeniedException.class)
    ProblemDetail onDenied(FlowAuthorizationDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({InputLimits.InputRejectedException.class, IllegalArgumentException.class})
    ProblemDetail onInvalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
