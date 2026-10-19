package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** Sensitive variables at rest on PostgreSQL (Testcontainers); skipped without Docker. */
class SensitiveVariablesStoragePostgresTest extends SensitiveVariablesStorageContract {

    @Override
    DataSource newMigratedDatabase() {
        return PostgresTestDatabase.freshMigratedDatabase();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }
}
