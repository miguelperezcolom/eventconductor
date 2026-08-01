package io.mateu.workflow.infra.config;

import io.mateu.workflow.webhook.ImportedDefinitionsRegistry;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({GitImportProperties.class, MessageApiProperties.class})
public class WorkflowEngineConfiguration {

    /**
     * Tracks which definitions came from which git repository so a webhook re-import can prune
     * removed ones. In-memory (instance-local, best-effort): the baseline resets on restart,
     * then repopulates on the next import. Declared with {@link ConditionalOnMissingBean} so a
     * durable implementation can replace it without touching this config.
     */
    @Bean
    @ConditionalOnMissingBean
    ImportedDefinitionsRegistry importedDefinitionsRegistry() {
        return new InMemoryImportedDefinitionsRegistry();
    }
}
