package io.mateu.workflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the {@link CallerResolver} the flow-authorization checks ask.
 *
 * <p>Every part is optional, and what is absent is absent rather than broken: without Spring
 * Security on the classpath there is no security-context resolver, without the opt-in there is no
 * forwarded-token resolver, and with neither the composite answers {@code null} to everything —
 * which denies any caller the moment a definition or a form requires something, and denies nobody
 * until then. That is the safe way for identity to be missing.
 *
 * <p>One bean rather than a bean per source, so that {@code @ConditionalOnMissingBean} means what it
 * says: a deployment that knows who its callers are — a header its mesh sets, a session, a customer
 * id — defines its own {@code CallerResolver} and everything downstream uses it without being told.
 * Registering the delegates as beans of their own would have satisfied that condition themselves and
 * the composite would never have been built.
 */
@Configuration
public class CallerResolverConfiguration {

    private static final String SECURITY_CONTEXT_HOLDER =
            "org.springframework.security.core.context.SecurityContextHolder";
    private static final String REQUEST_CONTEXT_HOLDER =
            "org.springframework.web.context.request.RequestContextHolder";

    /**
     * @param trustForwardedToken whether to read claims from a bearer token this application did not
     *                            verify. Only behind something that did — see
     *                            {@link ForwardedTokenCallerResolver}.
     * @param subjectClaims       claims to read the caller's id from, first match wins
     * @param scopeClaims         claims holding granted scopes; delimited string or array
     * @param roleClaims          claims holding granted roles; dotted names reach into nested objects
     */
    @Bean
    @ConditionalOnMissingBean(CallerResolver.class)
    public CallerResolver callerResolver(
            @Value("${workflow.security.trust-forwarded-token:false}") boolean trustForwardedToken,
            @Value("${workflow.security.claims.subject:preferred_username,sub}") List<String> subjectClaims,
            @Value("${workflow.security.claims.scopes:scope,scp}") List<String> scopeClaims,
            @Value("${workflow.security.claims.roles:realm_access.roles,roles}") List<String> roleClaims) {

        var classLoader = CallerResolverConfiguration.class.getClassLoader();
        var ordered = new ArrayList<CallerResolver>();
        // Verified first, asserted second. See CallerResolvers.
        if (ClassUtils.isPresent(SECURITY_CONTEXT_HOLDER, classLoader)) {
            ordered.add(new SpringSecurityCallerResolver());
        }
        if (trustForwardedToken && ClassUtils.isPresent(REQUEST_CONTEXT_HOLDER, classLoader)) {
            ordered.add(new ForwardedTokenCallerResolver(subjectClaims, scopeClaims, roleClaims));
        }
        return new CallerResolvers(ordered);
    }
}
