package io.mateu.workflow.infra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({GitImportProperties.class, MessageApiProperties.class})
public class WorkflowEngineConfiguration {
}
