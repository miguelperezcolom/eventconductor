package io.mateu.workflow.infra.config;

import io.mateu.workflow.webhook.ImportedDefinitionsRegistry;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({GitImportProperties.class, DirectoryImportProperties.class, MessageApiProperties.class,
        TaskContractDirectoryImportProperties.class, TaskContractGitImportProperties.class,
        EventDestinationsProperties.class})
public class WorkflowEngineConfiguration {

    /**
     * Tracks which definitions came from which git repository so a webhook re-import can prune
     * removed ones. In-memory (instance-local): the baseline resets on restart, then repopulates
     * on the next import — right for memory persistence, whose definitions do not survive a
     * restart either. With {@code workflow.persistence=jpa} the durable
     * {@code JpaImportedDefinitionsRegistry} ({@code @Primary}) is the one injected.
     */
    @Bean
    @ConditionalOnMissingBean
    ImportedDefinitionsRegistry importedDefinitionsRegistry() {
        return new InMemoryImportedDefinitionsRegistry();
    }
}
