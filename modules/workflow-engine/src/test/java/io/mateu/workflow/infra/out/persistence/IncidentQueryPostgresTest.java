package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** The incidents query on a real PostgreSQL (Testcontainers); skipped without Docker. */
class IncidentQueryPostgresTest extends IncidentQueryContract {

    @Override
    DataSource newMigratedDatabase() {
        return PostgresTestDatabase.freshMigratedDatabase();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }
}
