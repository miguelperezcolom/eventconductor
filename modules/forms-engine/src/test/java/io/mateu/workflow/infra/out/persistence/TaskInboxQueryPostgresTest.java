package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** The task inbox on a real PostgreSQL (Testcontainers); skipped without Docker. */
class TaskInboxQueryPostgresTest extends TaskInboxQueryContract {

    @Override
    DataSource newDatabase() {
        return FormsPostgresTestDatabase.freshDatabase();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }
}
