package io.mateu.workflow.infra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RuleGitImportProperties.class)
public class RulesEngineConfiguration {
}
