package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.httpworkers.HttpTaskQueue;
import io.mateu.workflow.application.httpworkers.HttpTaskQueueContract;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** The HTTP worker queue's SQL on H2 in PostgreSQL mode, over the real migrations (V50). */
class JdbcHttpTaskQueueH2Test extends HttpTaskQueueContract {

    @Override
    protected HttpTaskQueue newQueue() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:http-task-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        PostgresTestDatabase.migrate(ds);
        return new JdbcHttpTaskQueue(new JdbcTemplate(ds), new H2DbLockDialect(),
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }
}
