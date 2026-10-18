package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** The retention purge's SQL on PostgreSQL, the database production runs it on. Skipped without Docker. */
class ProcessPurgePostgresTest extends ProcessPurgeContract {

    @Override
    DataSource newMigratedDatabase() {
        return PostgresTestDatabase.freshMigratedDatabase();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }

    @Override
    DataSource newDatabaseMigratedTo(String version) {
        var ds = PostgresTestDatabase.freshDatabase();
        PostgresTestDatabase.migrateTo(ds, version);
        return ds;
    }
}
