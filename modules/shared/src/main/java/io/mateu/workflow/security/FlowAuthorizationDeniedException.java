package io.mateu.workflow.security;

import java.util.List;

/**
 * A caller asked for something the flow says they may not have.
 *
 * <p>{@link RuntimeException} deliberately, and unhandled deliberately, because what to do about it
 * depends on the door it came through and every door already knows: the UI renders it as the message
 * on the page, the upstream consumer parks it on the dead-letter destination — a creation refused for
 * lack of a scope will be refused for the same reason on every redelivery — and an MCP call gets the
 * text. It is not one of the failures {@code EventFailures} calls retryable, for that reason.
 *
 * <p>The message names what was missing rather than saying "forbidden". A bare refusal is useless to
 * the operator reading the dead-letter topic and to the audit trail; the scope names are not secret,
 * and withholding them protects nobody who could not already read the definition.
 */
public class FlowAuthorizationDeniedException extends RuntimeException {

    public FlowAuthorizationDeniedException(String message) {
        super(message);
    }

    public static FlowAuthorizationDeniedException of(String what, String subject,
                                               List<String> missingScopes, List<String> missingRoles) {
        var missing = new StringBuilder();
        if (!missingScopes.isEmpty()) {
            missing.append(" scope(s) ").append(missingScopes);
        }
        if (!missingRoles.isEmpty()) {
            if (!missing.isEmpty()) {
                missing.append(" and");
            }
            missing.append(" role(s) ").append(missingRoles);
        }
        return new FlowAuthorizationDeniedException(
                (subject == null ? "An unidentified caller" : "Caller '" + subject + "'")
                        + " may not " + what + ": missing" + missing);
    }
}
