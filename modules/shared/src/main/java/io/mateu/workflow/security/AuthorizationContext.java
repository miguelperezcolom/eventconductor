package io.mateu.workflow.security;

import java.util.List;
import java.util.Set;

/**
 * A <b>snapshot</b> of the caller's authorization, captured once when a process is created and carried
 * with the process for its whole life. It is what the flow-authorization checks evaluate against —
 * "does the caller who started this process hold the scopes this step requires?".
 *
 * <p><b>Deliberately a snapshot, not a live token.</b> A JWT lives minutes; a workflow lives minutes to
 * weeks, so re-using the original token to authorize a step that runs a day later is impossible — it has
 * expired. So at creation the engine extracts the identity and the granted scopes/roles from the
 * validated token and keeps just those. The trade is explicit: no revocation and no mid-flight role
 * change take effect (the process runs with the authority it was started with), in exchange for an
 * authorization model that works for long-running flows at all. Downstream calls a worker makes are a
 * separate concern — a snapshot of scopes is not a bearer credential, so the worker authenticates to
 * downstream services itself; this context tells it (and the engine) <em>who</em> the flow runs for.
 *
 * <p>It carries no secret (identity + granted scope names, not the token), but it is still
 * authorization data: it must never be logged and is redacted from the read model and the UI.
 *
 * @param subject the caller's stable id (the token's {@code sub}), for audit and for the worker's
 *                on-behalf-of decisions; {@code null} for a system-initiated process (cron, a timer).
 * @param scopes  the granted scope names at creation time.
 * @param roles   the granted role names at creation time.
 */
public record AuthorizationContext(String subject, List<String> scopes, List<String> roles) {

    /** A system identity — a process with no human caller (cron, timer, an internal spawn). */
    public static final AuthorizationContext SYSTEM = new AuthorizationContext("system", List.of(), List.of());

    public AuthorizationContext {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public Set<String> scopeSet() {
        return Set.copyOf(scopes);
    }

    public Set<String> roleSet() {
        return Set.copyOf(roles);
    }
}
