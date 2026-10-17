package io.mateu.workflow.infra.out.persistence;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** The analytics read model on H2 in PostgreSQL mode — the embedded deployment, and every build. */
class AnalyticsReadModelH2Test extends AnalyticsReadModelContract {

    @Override
    DataSource newMigratedDatabase() {
        // DB_CLOSE_DELAY=-1: the context's pool and the test's own connections see one database.
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:analytics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        PostgresTestDatabase.migrate(ds);
        return ds;
    }

    @Override
    DbLockDialect dialect() {
        return new H2DbLockDialect();
    }

    @Override
    void dispose(DataSource dataSource) {
        // In-memory; nothing to release.
    }
}
