package io.mateu.workflow.infra.config;

import io.mateu.workflow.webhook.ImportedDefinitionsRegistry;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RuleGitImportProperties.class, RuleDirectoryImportProperties.class})
public class RulesEngineConfiguration {

    /**
     * Tracks which rules came from which git repository so a webhook re-import can prune
     * removed ones. In-memory, best-effort; {@link ConditionalOnMissingBean} lets a durable
     * implementation (or another engine's bean, in a combined app) take precedence.
     */
    @Bean
    @ConditionalOnMissingBean
    ImportedDefinitionsRegistry importedDefinitionsRegistry() {
        return new InMemoryImportedDefinitionsRegistry();
    }
}
