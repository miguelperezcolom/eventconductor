package io.mateu.workflow.infra.out.persistence;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** The process-update store on H2 in PostgreSQL mode — every build. */
class ProcessUpdateStoreH2Test extends ProcessUpdateStoreContract {

    @Override
    DataSource newMigratedDatabase() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:updates-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        PostgresTestDatabase.migrate(ds);
        return ds;
    }

    @Override
    void dispose(DataSource dataSource) {
    }
}
