package io.mateu.workflow.infra.out.persistence;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** The V56 stores on H2 in PostgreSQL mode — the embedded deployment, and every build. */
class FlowControlStoresH2Test extends FlowControlStoresContract {

    @Override
    DataSource newMigratedDatabase() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:flow-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        PostgresTestDatabase.migrate(ds);
        return ds;
    }

    @Override
    void dispose(DataSource dataSource) {
        // In-memory; nothing to release.
    }
}
