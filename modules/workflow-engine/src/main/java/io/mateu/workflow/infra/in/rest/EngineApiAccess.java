package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.security.console.ConsoleAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Who may call the engine's REST API (/workflow/api/**: messages, events, triggers, schedules, sync
 * invocations, processes) — one rule for every controller:
 *
 * <ul>
 *   <li>{@code workflow.message-api.api-key} set: the {@code X-Api-Key} header must match it.</li>
 *   <li>No key, but a Spring Security filter chain protects the application: the request has passed
 *       the application's own authentication, so it comes in.</li>
 *   <li>No key and no security: refused with 401, unless {@code workflow.message-api.allow-anonymous}
 *       is true. Since 2.27.0 — before, such an engine answered anybody who could reach the port.</li>
 * </ul>
 *
 * <p>At startup it records whether a filter chain exists (Spring Security is optional, so it is looked
 * up by name) and says, in one line, how the API is protected and how the consoles are: the admin UI,
 * the MCP endpoints, the rule-read endpoints and the task-project zips follow
 * {@link ConsoleAccess} — the same rule without the api key, which a browser cannot send.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Slf4j
public class EngineApiAccess implements SmartInitializingSingleton {

    static final String SECURITY_FILTER_CHAIN = ConsoleAccess.SECURITY_FILTER_CHAIN;
    static final String MISSING_OR_INVALID_KEY = "Missing or invalid X-Api-Key header";
    static final String ANONYMOUS_REFUSED = "The engine API refuses anonymous requests: set workflow.message-api.api-key"
            + " and send it as X-Api-Key, protect the application with Spring Security, or set"
            + " workflow.message-api.allow-anonymous=true for an isolated deployment.";

    /** The consoles do not take the api key (a browser cannot send one): they follow ConsoleAccess. */
    static final String CONSOLES_PREFIX = " Consoles — ";

    private final MessageApiProperties apiProperties;
    private final ListableBeanFactory beans;

    private final String securityFilterChainType;

    @Autowired
    public EngineApiAccess(MessageApiProperties apiProperties, ListableBeanFactory beans) {
        this(apiProperties, beans, SECURITY_FILTER_CHAIN);
    }

    EngineApiAccess(MessageApiProperties apiProperties, ListableBeanFactory beans, String securityFilterChainType) {
        this.apiProperties = apiProperties;
        this.beans = beans;
        this.securityFilterChainType = securityFilterChainType;
    }

    @Override
    public void afterSingletonsInstantiated() {
        apiProperties.setSecuredByFilterChain(hasFilterChain(beans, securityFilterChainType));
        boolean chain = apiProperties.isSecuredByFilterChain();
        boolean anonymous = apiProperties.isAllowAnonymous();
        var consoles = CONSOLES_PREFIX + ConsoleAccess.status(chain, anonymous);
        boolean open = !chain && anonymous;
        if (!engineApiPresent(beans)) {
            logAt(chain, "EventConductor access." + consoles);
        } else if (hasKey(apiProperties)) {
            logAt(chain, "The engine's REST API (/workflow/api/**) requires the X-Api-Key header to"
                    + " match workflow.message-api.api-key." + consoles);
        } else if (chain) {
            log.info("The engine's REST API (/workflow/api/**) is protected by the application's Spring Security;"
                    + " no workflow.message-api.api-key is required." + consoles);
        } else if (open) {
            log.warn("The engine's REST API (/workflow/api/**: processes, messages, events, schedules, sync invocations) is OPEN:"
                    + " workflow.message-api.allow-anonymous is true, no api-key is set and no Spring Security filter"
                    + " chain is configured, so anyone who can reach this port can start processes, send messages"
                    + " and create schedules." + consoles);
        } else {
            log.warn("The engine's REST API (/workflow/api/**) refuses every request (401): no"
                    + " workflow.message-api.api-key is set and no Spring Security filter chain is configured. Set the"
                    + " key (sent as X-Api-Key), secure the application, or set"
                    + " workflow.message-api.allow-anonymous=true for an isolated deployment." + consoles);
        }
    }

    private static void logAt(boolean info, String message) {
        if (info) {
            log.info(message);
        } else {
            log.warn(message);
        }
    }

    /**
     * Lets the request in or throws 401.
     *
     * @param apiProperties the engine's API settings (the controllers hold them)
     * @param received      the {@code X-Api-Key} header, or null
     */
    public static void verify(MessageApiProperties apiProperties, String received) {
        if (hasKey(apiProperties)) {
            // constant-time comparison to prevent timing attacks
            if (received == null || !MessageDigest.isEqual(apiProperties.getApiKey().getBytes(StandardCharsets.UTF_8),
                    received.getBytes(StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MISSING_OR_INVALID_KEY);
            }
            return;
        }
        if (apiProperties.isSecuredByFilterChain() || apiProperties.isAllowAnonymous()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ANONYMOUS_REFUSED);
    }

    private static boolean hasKey(MessageApiProperties apiProperties) {
        return apiProperties.getApiKey() != null && !apiProperties.getApiKey().isBlank();
    }

    static boolean engineApiPresent(ListableBeanFactory beans) {
        return beans.getBeanNamesForType(MessageRestController.class, false, false).length > 0
                || beans.getBeanNamesForType(EventRestController.class, false, false).length > 0
                || beans.getBeanNamesForType(ScheduleRestController.class, false, false).length > 0
                || beans.getBeanNamesForType(SyncInvocationController.class, false, false).length > 0
                || beans.getBeanNamesForType(ProcessRestController.class, false, false).length > 0;
    }

    /** Whether a bean of {@code securityFilterChainType} exists (false when Spring Security is not on the classpath). */
    static boolean hasFilterChain(ListableBeanFactory beans, String securityFilterChainType) {
        return ConsoleAccess.hasFilterChain(beans, securityFilterChainType);
    }
}
