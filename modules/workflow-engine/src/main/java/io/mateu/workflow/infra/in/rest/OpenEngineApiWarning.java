package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.infra.config.MessageApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Says so, once, at startup, when the engine's REST API answers anybody.
 *
 * <p>The message, event, schedule and sync-invocation endpoints accept a request without credentials
 * when {@code workflow.message-api.api-key} is blank — the documented default, so an embedded app
 * works out of the box — and nothing else stands in front of them unless the host application
 * configures Spring Security. That is fine on a laptop and not fine on a network: anyone who can reach
 * the port can start processes, send messages and create schedules. Failing closed instead would break
 * every existing embedded deployment on upgrade, so this warns rather than refuses.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Slf4j
public class OpenEngineApiWarning {

    static final String SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";

    private final MessageApiProperties apiProperties;
    private final ListableBeanFactory beans;

    public OpenEngineApiWarning(MessageApiProperties apiProperties, ListableBeanFactory beans) {
        this.apiProperties = apiProperties;
        this.beans = beans;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfOpen() {
        if (apiIsOpen(apiProperties.getApiKey(), beans, SECURITY_FILTER_CHAIN)) {
            log.warn("The engine's REST API (/workflow/api/**: messages, events, schedules, sync invocations) is OPEN:"
                    + " workflow.message-api.api-key is not set and no Spring Security filter chain is configured,"
                    + " so anyone who can reach this port can start processes, send messages and create schedules."
                    + " Set workflow.message-api.api-key (sent as X-Api-Key) or secure the application with Spring"
                    + " Security before exposing it to a network.");
        }
    }

    /**
     * Whether the API answers anybody: no API key, the engine's controllers present, and no bean of
     * {@code securityFilterChainType} (looked up by name, since Spring Security is optional).
     */
    static boolean apiIsOpen(String apiKey, ListableBeanFactory beans, String securityFilterChainType) {
        if (apiKey != null && !apiKey.isBlank()) {
            return false;
        }
        if (beans.getBeanNamesForType(MessageRestController.class, false, false).length == 0
                && beans.getBeanNamesForType(EventRestController.class, false, false).length == 0
                && beans.getBeanNamesForType(ScheduleRestController.class, false, false).length == 0
                && beans.getBeanNamesForType(SyncInvocationController.class, false, false).length == 0) {
            return false;
        }
        try {
            var chainType = ClassUtils.forName(securityFilterChainType, OpenEngineApiWarning.class.getClassLoader());
            return beans.getBeanNamesForType(chainType, false, false).length == 0;
        } catch (ClassNotFoundException | LinkageError noSpringSecurity) {
            return true;
        }
    }
}
