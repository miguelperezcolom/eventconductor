package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One PostgreSQL container for the whole test JVM, and a fresh, empty database on it for every
 * caller — so the tests that need the real thing (advisory locks, {@code SKIP LOCKED},
 * {@code LISTEN/NOTIFY}, the read model's SQL as PostgreSQL types it) pay for one container start,
 * not one per class, and still never see each other's rows.
 *
 * <p>Without Docker every caller is <em>skipped</em> (a JUnit assumption), never failed: a plain
 * {@code mvn verify} on a machine without Docker stays green, and wherever Docker is present — CI
 * runners included — these tests run and count towards this module's coverage. Same image as
 * workflow-dist-e2e.
 */
final class PostgresTestDatabase {

    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private static PostgreSQLContainer<?> container;
    private static Boolean dockerAvailable;

    private PostgresTestDatabase() {
    }

    /**
     * Skips the calling test when there is no Docker to run PostgreSQL in — or when the build asked
     * for that explicitly with {@code -Dworkflow.tests.postgres=false}, for a machine whose Docker is
     * present but should not be used.
     */
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
                    .withDatabaseName("workflow")
                    .withUsername("workflow")
                    .withPassword("secret");
            started.start();
            container = started;
        }
        return container;
    }

    /** A new, empty database (no schema), behind a small Hikari pool. */
    static HikariDataSource freshDatabase() {
        var c = container();
        var name = "t" + DATABASES.incrementAndGet();
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
        ds.setMaximumPoolSize(8);
        ds.setPoolName("pg-test-" + name);
        return ds;
    }

    /** A new database with the engine's own migrations applied, as a deployment would have it. */
    static HikariDataSource freshMigratedDatabase() {
        var ds = freshDatabase();
        migrate(ds);
        return ds;
    }

    /** Applies the engine's migrations ({@code db/migration/workflow}) to the given database. */
    static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/workflow")
                .table("workflow_schema_history")
                .load()
                .migrate();
    }

    /** Applies the engine's migrations up to and including {@code version} — a database an older release left. */
    static void migrateTo(DataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/workflow")
                .table("workflow_schema_history")
                .target(version)
                .load()
                .migrate();
    }
}
