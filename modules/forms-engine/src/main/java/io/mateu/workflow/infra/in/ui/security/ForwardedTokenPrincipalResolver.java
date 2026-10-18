package io.mateu.workflow.infra.in.ui.security;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.security.CallerIdentity;
import io.mateu.uidl.security.PrincipalResolver;
import io.mateu.workflow.security.ForwardedTokenCallerResolver;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells Mateu who is on the other side of the task pages when the deployment says a forwarded
 * bearer token is to be believed — the same rule, and the same claims, the engine applies to its own
 * API ({@code workflow.security.trust-forwarded-token}, {@code workflow.security.claims.*}).
 *
 * <p>Mateu (from 3.0-alpha.409) no longer reads a caller from an unverified token by itself: it
 * takes the principal the application authenticated, or a {@link PrincipalResolver}'s answer, or
 * nobody. Without this bridge a deployment behind a token-verifying gateway — the shape the flag
 * exists for — would see every claim and completion refused, because nobody would be signed in.
 *
 * <p>An identity this application authenticated still wins: when the request carries a principal,
 * this resolver steps aside and Mateu uses it. With the flag off (the default) it never answers,
 * so a token nobody checked is never believed.
 */
@Component
public class ForwardedTokenPrincipalResolver implements PrincipalResolver {

    private final boolean trustForwardedToken;
    private final ForwardedTokenCallerResolver tokens;

    public ForwardedTokenPrincipalResolver(
            @Value("${workflow.security.trust-forwarded-token:false}") boolean trustForwardedToken,
            @Value("${workflow.security.claims.subject:preferred_username,sub}") List<String> subjectClaims,
            @Value("${workflow.security.claims.scopes:scope,scp}") List<String> scopeClaims,
            @Value("${workflow.security.claims.roles:realm_access.roles,roles}") List<String> roleClaims) {
        this.trustForwardedToken = trustForwardedToken;
        this.tokens = new ForwardedTokenCallerResolver(subjectClaims, scopeClaims, roleClaims);
    }

    @Override
    public Optional<CallerIdentity> resolve(HttpRequest httpRequest) {
        if (!trustForwardedToken || httpRequest == null) {
            return Optional.empty();
        }
        try {
            if (httpRequest.getUserPrincipal() != null) {
                return Optional.empty();
            }
        } catch (RuntimeException ignored) {
            // an adapter that cannot tell: fall through to the token
        }
        String header;
        try {
            header = httpRequest.getHeaderValue("Authorization");
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        var caller = tokens.fromAuthorizationHeader(header);
        if (caller == null || caller.subject() == null) {
            return Optional.empty();
        }
        return Optional.of(new CallerIdentity(
                caller.subject(), caller.roles(), List.of(), caller.scopes(), List.of()));
    }
}
