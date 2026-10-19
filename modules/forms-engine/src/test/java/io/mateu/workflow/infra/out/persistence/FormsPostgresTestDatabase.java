package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One PostgreSQL container for the test JVM and a fresh, empty database on it per caller — the forms
 * engine's twin of the workflow engine's {@code PostgresTestDatabase}. Without Docker (or with
 * {@code -Dworkflow.tests.postgres=false}) every caller is skipped, never failed.
 */
final class FormsPostgresTestDatabase {

    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private static PostgreSQLContainer<?> container;
    private static Boolean dockerAvailable;

    private FormsPostgresTestDatabase() {
    }

    static synchronized void assumeDocker() {
        assumeTrue(!"false".equalsIgnoreCase(System.getProperty("workflow.tests.postgres")),
                "PostgreSQL tests switched off (-Dworkflow.tests.postgres=false)");
        if (dockerAvailable == null) {
            boolean available;
            try {
                available = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable e) {
                available = false;
            }
            dockerAvailable = available;
        }
        assumeTrue(dockerAvailable, "Docker is not available: PostgreSQL tests are skipped");
    }

    private static synchronized PostgreSQLContainer<?> container() {
        assumeDocker();
        if (container == null) {
            var started = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("forms")
                    .withUsername("forms")
                    .withPassword("secret");
            started.start();
            container = started;
        }
        return container;
    }

    /** A new, empty database (no schema), behind a small Hikari pool. */
    static HikariDataSource freshDatabase() {
        var c = container();
        var name = "f" + DATABASES.incrementAndGet();
        try (var con = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             var statement = con.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        var ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(5432) + "/" + name);
        ds.setUsername(c.getUsername());
        ds.setPassword(c.getPassword());
        ds.setMaximumPoolSize(4);
        ds.setPoolName("forms-pg-test-" + name);
        return ds;
    }
}
