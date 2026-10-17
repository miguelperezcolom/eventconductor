package io.mateu.workflow.security;

import java.util.List;
import java.util.Set;

/**
 * A <b>snapshot</b> of the caller's authorization, taken at the door that accepted their request and
 * carried on it from there. It is what the flow-authorization checks evaluate against — "does this
 * caller hold what this workflow requires to be started, or what this form requires to be worked
 * on?" — and it is produced by a {@link CallerResolver}, never read from a request twice.
 *
 * <p>It is carried on the request, not stored on the process: nothing persists it yet, so a check
 * that runs later than the creation — a {@code Step}'s own requirements, which parse and are not
 * enforced — has nothing to evaluate against. That is the next piece, and it needs a column.
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

    /**
     * A system identity — a process with no human caller (cron, timer, an internal spawn).
     *
     * <p>Recognised by <em>identity</em> ({@link #isSystem}), never by value: a caller resolver that
     * builds {@code new AuthorizationContext("system", [], [])} — a login or a token whose subject
     * happens to be "system" — is an ordinary caller and is judged like any other. Only the engine's own
     * code holds this instance. The one way back to it from outside the JVM is deserialization
     * ({@link #fromJson}) of a creation request on the engine's own upstream/ingress topics, which are
     * trusted transport: a producer on them already chooses the caller's scopes outright.
     */
    public static final AuthorizationContext SYSTEM = new AuthorizationContext("system", List.of(), List.of());

    public AuthorizationContext {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** Whether {@code caller} is the engine acting for itself — the {@link #SYSTEM} instance, not a look-alike. */
    public static boolean isSystem(AuthorizationContext caller) {
        return caller == SYSTEM;
    }

    /**
     * How a context is read back from JSON (a {@code ProcessCreationRequested} on the engine's own
     * topics): the system identity a cron start or an inbound event was published with comes back as
     * {@link #SYSTEM} itself, so it keeps meaning "the engine" across the Kafka hop.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static AuthorizationContext fromJson(@com.fasterxml.jackson.annotation.JsonProperty("subject") String subject,
                                                @com.fasterxml.jackson.annotation.JsonProperty("scopes") List<String> scopes,
                                                @com.fasterxml.jackson.annotation.JsonProperty("roles") List<String> roles) {
        var context = new AuthorizationContext(subject, scopes, roles);
        return SYSTEM.equals(context) ? SYSTEM : context;
    }

    public Set<String> scopeSet() {
        return Set.copyOf(scopes);
    }

    public Set<String> roleSet() {
        return Set.copyOf(roles);
    }
}
