package io.mateu.workflow.infra.in.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestBodyLimitFilter} on the paths the engine and its sibling modules open to
 * the outside, and on no others.
 *
 * <p>The patterns are listed here rather than discovered, because a filter is matched by path and
 * needs no controller to exist: a deployment without the forms or rules engine simply never matches
 * theirs. Registering the filter as a {@code @Component} would have been shorter and would have
 * applied it to every request the host application serves, which is not this module's business.
 *
 * <p>The default is well above {@code InputLimits.MAX_TOTAL_LENGTH} on purpose. The two limits are
 * meant to speak in that order: a body that is merely larger than the engine accepts should be
 * refused by the content check, with a message naming the field and the number, and only something
 * absurd enough that reading it is itself the problem should be cut off unread.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestBodyLimitConfiguration {

    @Bean
    public FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
            @Value("${workflow.rest.max-body-bytes:16777216}") long maxBytes) {
        var registration = new FilterRegistrationBean<>(new RequestBodyLimitFilter(maxBytes));
        registration.addUrlPatterns(
                "/workflow/api/*",
                "/workflow/webhooks/*",
                "/forms/webhooks/*",
                "/rules/webhooks/*");
        // Ahead of anything that might read the body for its own reasons.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("workflowRequestBodyLimitFilter");
        return registration;
    }
}
