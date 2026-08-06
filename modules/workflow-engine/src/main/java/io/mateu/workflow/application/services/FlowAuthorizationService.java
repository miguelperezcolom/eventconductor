package io.mateu.workflow.application.services;

import io.mateu.workflow.security.AuthorizationContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Decides whether a caller may run a flow or a step, by comparing the {@link AuthorizationContext}
 * snapshot captured at creation against the scopes/roles a workflow definition (or a single step)
 * declares it requires. The pure evaluation at the heart of flow authorization — no I/O, no token
 * validation (that happens once, at ingress), just: does this caller hold what this requires.
 *
 * <p><b>Requires-all, fail-closed.</b> The caller must hold <em>every</em> required scope and role (an
 * AND, not an ANY) — the safe reading of "this step needs scopes X and Y". A declaration with no
 * requirements is open to anyone (it declares no restriction). A missing caller ({@code null}) holds
 * nothing, so it is denied the moment anything is required — a step that requires a scope cannot be run
 * by an unauthenticated or unknown principal.
 */
@Service
public class FlowAuthorizationService {

    /**
     * The outcome, with the specific unmet requirements so the caller can be told exactly why — a bare
     * "forbidden" is useless to the operator and to the audit trail.
     */
    public record Decision(boolean allowed, List<String> missingScopes, List<String> missingRoles) {
        public static Decision granted() {
            return new Decision(true, List.of(), List.of());
        }
    }

    public Decision authorize(AuthorizationContext caller,
                              List<String> requiredScopes,
                              List<String> requiredRoles) {
        var required = normalise(requiredScopes);
        var requiredR = normalise(requiredRoles);
        if (required.isEmpty() && requiredR.isEmpty()) {
            return Decision.granted();
        }
        var heldScopes = caller == null ? java.util.Set.<String>of() : caller.scopeSet();
        var heldRoles = caller == null ? java.util.Set.<String>of() : caller.roleSet();
        var missingScopes = required.stream().filter(s -> !heldScopes.contains(s)).toList();
        var missingRoles = requiredR.stream().filter(r -> !heldRoles.contains(r)).toList();
        return new Decision(missingScopes.isEmpty() && missingRoles.isEmpty(), missingScopes, missingRoles);
    }

    private static List<String> normalise(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }
}
