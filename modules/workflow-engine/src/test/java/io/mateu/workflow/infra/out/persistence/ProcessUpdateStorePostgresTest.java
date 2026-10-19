package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** The process-update store on PostgreSQL — its unique key under concurrent inserts included. Skipped without Docker. */
class ProcessUpdateStorePostgresTest extends ProcessUpdateStoreContract {

    @Override
    DataSource newMigratedDatabase() {
        return PostgresTestDatabase.freshMigratedDatabase();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }
}
