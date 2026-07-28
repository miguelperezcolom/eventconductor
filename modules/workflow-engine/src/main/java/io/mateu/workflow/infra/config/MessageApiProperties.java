package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow.message-api")
@Getter
@Setter
public class MessageApiProperties {

    /**
     * Optional API key required in the X-Api-Key header of POST /workflow/api/messages.
     * Leave blank to accept unauthenticated messages (compared in constant time when set).
     */
    private String apiKey;
}
