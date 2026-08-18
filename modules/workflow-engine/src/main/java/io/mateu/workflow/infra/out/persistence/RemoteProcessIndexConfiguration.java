package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.processindex.JdbcProcessIndexStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Points the engine's read model at the <b>read database</b> when a standalone projector maintains it
 * ({@code workflow.projection.mode=remote}).
 *
 * <p>The engine still needs to read the index even when it no longer writes it: the command publisher
 * resolves {@code processId → shardId} from it to route a retry/cancel/pause to the owning shard, and
 * the UI and MCP query tools list and count from it. In remote mode those questions have a
 * fleet-wide answer for the first time — the local index only ever knew this shard's processes.
 *
 * <p>{@link Primary} over the local adapters rather than switching them off, because the choice is
 * one of <em>which</em> store answers, and a condition that removed the local one would have to be
 * kept in step in two modules. The local {@code process_index} table stays in the write database,
 * mapped and empty; it costs nothing and it is what a rollback to embedded mode lands back on.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.projection.mode", havingValue = "remote")
public class RemoteProcessIndexConfiguration {

    @Bean(destroyMethod = "close")
    public ProcessIndexDataSource processIndexDataSource(
            @Value("${workflow.projection.datasource.url}") String url,
            @Value("${workflow.projection.datasource.username:}") String username,
            @Value("${workflow.projection.datasource.password:}") String password,
            // Small on purpose: this pool serves point lookups from the command path and the
            // occasional listing, not the per-step write traffic the main pool is sized for.
            @Value("${workflow.projection.datasource.pool-size:4}") int poolSize) {
        return new ProcessIndexDataSource(url, username, password, poolSize);
    }

    @Bean
    @Primary
    public ProcessIndexRepository remoteProcessIndexRepository(ProcessIndexDataSource dataSource) {
        return new JdbcProcessIndexStore(dataSource.dataSource());
    }
}
