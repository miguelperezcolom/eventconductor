package io.mateu.workflow.infra.out.persistence;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/** The task inbox on H2 in PostgreSQL mode — every build. */
class TaskInboxQueryH2Test extends TaskInboxQueryContract {

    @Override
    DataSource newDatabase() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:forms-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    }

    @Override
    void dispose(DataSource dataSource) {
    }
}
