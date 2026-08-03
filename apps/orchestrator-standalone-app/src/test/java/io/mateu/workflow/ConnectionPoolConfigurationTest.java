package io.mateu.workflow;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection pool is sized on purpose — a process update holds a connection for its critical
 * section, so the pool caps how many in-flight processes a pod can advance at once, and the
 * shipped value is argued from the threads that hold one at once (both consumer bindings, the
 * relay, the timeout scanner, the UI).
 *
 * <p>None of that reasoning had any effect. The properties sat under {@code spring.hikari.*},
 * which Boot binds nothing from — the only namespace is {@code spring.datasource.hikari.*} — so
 * every deployment silently ran HikariCP's default of 10 and a 30 s timeout, however carefully
 * {@code DB_POOL_SIZE} was set. The Helm chart dutifully passed a value that went nowhere.
 *
 * <p>Which is why this asserts the effective pool on the real configuration rather than the
 * presence of a property: a misplaced key is exactly the failure that reading the YAML back would
 * not have caught.
 */
@SpringBootTest
class ConnectionPoolConfigurationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void theShippedConfigurationActuallySizesThePool() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        var hikari = (HikariDataSource) dataSource;

        assertThat(hikari.getMaximumPoolSize()).isEqualTo(16);   // DB_POOL_SIZE default
        assertThat(hikari.getConnectionTimeout()).isEqualTo(20_000);  // DB_CONNECTION_TIMEOUT default
    }
}
