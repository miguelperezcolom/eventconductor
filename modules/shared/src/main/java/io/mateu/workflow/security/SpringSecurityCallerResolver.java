package io.mateu.workflow.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;

/**
 * The caller as the application itself authenticated them — the answer whenever the app secures its
 * own endpoints rather than trusting something in front of it. That covers the standalone apps'
 * HTTP Basic and any deployment that configures a resource server, and it is the answer to prefer,
 * because these claims were verified here rather than asserted by somebody else.
 *
 * <p>Spring's two authority prefixes are read as what they mean: {@code ROLE_x} is the role
 * {@code x} and {@code SCOPE_x} is the scope {@code x} — the same spelling
 * {@code hasRole}/{@code hasAuthority} use, so a deployment's existing authorities work without
 * being restated. An authority with neither prefix is taken as a role, because that is what an
 * unprefixed authority means to everyone who has ever configured one.
 */
public class SpringSecurityCallerResolver implements CallerResolver {

    static final String ROLE_PREFIX = "ROLE_";
    static final String SCOPE_PREFIX = "SCOPE_";

    /** Spring's name for "nobody is logged in", which is not the same as a caller with no roles. */
    static final String ANONYMOUS = "anonymousUser";

    @Override
    public AuthorizationContext current() {
        Authentication authentication;
        try {
            authentication = SecurityContextHolder.getContext().getAuthentication();
        } catch (RuntimeException e) {
            // No context on this thread. Not knowing is an answer; see CallerResolver.
            return null;
        }
        if (authentication == null || !authentication.isAuthenticated()
                || ANONYMOUS.equals(authentication.getName())) {
            return null;
        }
        var scopes = new ArrayList<String>();
        var roles = new ArrayList<String>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            var authority = granted == null ? null : granted.getAuthority();
            if (authority == null || authority.isBlank()) {
                continue;
            }
            if (authority.startsWith(SCOPE_PREFIX)) {
                scopes.add(authority.substring(SCOPE_PREFIX.length()));
            } else if (authority.startsWith(ROLE_PREFIX)) {
                roles.add(authority.substring(ROLE_PREFIX.length()));
            } else {
                roles.add(authority);
            }
        }
        return new AuthorizationContext(authentication.getName(), scopes, roles);
    }
}
