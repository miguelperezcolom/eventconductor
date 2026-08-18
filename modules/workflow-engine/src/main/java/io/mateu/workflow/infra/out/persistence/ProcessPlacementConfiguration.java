package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.processindex.JdbcProcessPlacementStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The placement store, present only where one is configured
 * ({@code workflow.sharding.placement.datasource.url}).
 *
 * <p>Opt-in like the rest of sharding, and gated on the URL rather than on a boolean so that there is
 * no way to switch it on without saying where it lives. A sharded deployment that leaves it out gets a
 * loud warning from {@code IngressRouter} explaining what it is risking; a single-cluster deployment
 * never sets it and never sees any of this.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.sharding.placement.datasource.url")
public class ProcessPlacementConfiguration {

    @Bean(destroyMethod = "close")
    public ProcessPlacementDataSource processPlacementDataSource(
            @Value("${workflow.sharding.placement.datasource.url}") String url,
            @Value("${workflow.sharding.placement.datasource.username:}") String username,
            @Value("${workflow.sharding.placement.datasource.password:}") String password,
            // One insert per created process, so this pool sizes with the creation rate, not with the
            // per-step write traffic the engine's own pool is sized for.
            @Value("${workflow.sharding.placement.datasource.pool-size:4}") int poolSize) {
        return new ProcessPlacementDataSource(url, username, password, poolSize);
    }

    @Bean
    public ProcessPlacementRepository processPlacementRepository(ProcessPlacementDataSource dataSource) {
        return new JdbcProcessPlacementStore(dataSource.dataSource());
    }
}
