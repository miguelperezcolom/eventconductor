package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The answer to a start (or invocation) of a definition the engine does not hold.
 *
 * <p>Normally 404. But while the application is not ready yet — readiness is
 * {@link ReadinessState#ACCEPTING_TRAFFIC} only once every startup runner, the git import of
 * workflow definitions among them, has finished — the definition may simply not be imported yet:
 * then 503 with {@code Retry-After}, which a client (or a load balancer) retries, instead of a 404
 * it would take as final. Local definitions (classpath, directories) are imported before the web
 * server starts (see {@code StartupDefinitionImports}), so this window only exists for git imports.
 */
final class UnknownDefinitionResponses {

    /** Seconds a client is asked to wait before trying a start again during startup. */
    static final String RETRY_AFTER_SECONDS = "1";

    private UnknownDefinitionResponses() {
    }

    static ResponseEntity<ProblemDetail> of(UnknownWorkflowDefinitionException e, ApplicationAvailability availability) {
        if (availability != null && availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Workflow definition '" + e.workflowDefinitionId() + "' is not loaded yet: the engine is still"
                            + " starting and importing definitions; retry shortly.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(problem);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }
}
