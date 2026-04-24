package io.mateu.workflow.controlplaneservice.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@ConfigurationProperties(prefix = "cloudflare")
@Getter@Setter
public class CloudflareProperties {
    private String apiToken;
    private String accountId;
    private String namespaceId;
    private String baseUrl;

    // getters/setters
}