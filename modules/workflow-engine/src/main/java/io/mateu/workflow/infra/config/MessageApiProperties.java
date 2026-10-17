package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow.message-api")
@Getter
@Setter
public class MessageApiProperties {

    /**
     * API key required in the X-Api-Key header of the engine's REST API (/workflow/api/**: messages,
     * events, triggers, schedules, sync invocations), compared in constant time.
     */
    private String apiKey;

    /**
     * Accept requests without credentials when no {@link #apiKey} is set and no Spring Security filter
     * chain protects the application. Off by default since 2.27.0: an engine reachable on a network
     * must not let anybody start processes, send messages or create schedules. Turn it on only for a
     * local or otherwise isolated deployment.
     */
    private boolean allowAnonymous;

    /**
     * Whether a Spring Security filter chain protects the application — set by the engine at startup
     * ({@code EngineApiAccess}), not meant to be configured. When it does, a request that reached a
     * controller has already passed the application's own authentication, so no key is required.
     */
    private boolean securedByFilterChain;
}
