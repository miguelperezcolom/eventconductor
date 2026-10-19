package db.migration.workflow;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The indexes 2.33 adds to tables that already hold data — {@code process_entity} (the runs of a business key that
 * handed it over) and {@code http_task} (the priority order of the HTTP worker queue).
 *
 * <p>A plain {@code CREATE INDEX} blocks every write to the table while it builds, which on a large
 * {@code process_entity} is minutes of an engine that cannot move a process. On PostgreSQL they are built
 * {@code CONCURRENTLY} — writes carry on — which cannot run inside a transaction: hence a Java migration that runs
 * outside one ({@link #canExecuteInTransaction()}). A concurrent build that failed half-way leaves an INVALID index
 * behind, which {@code IF NOT EXISTS} would keep: it is dropped and built again. Other databases get the plain
 * statement, as every migration before this one.
 *
 * <p>A concurrent build waits for every transaction older than itself to end. The engine runs its migrations with
 * a session-level Flyway lock ({@code ManagedSchema}), but a Flyway configured elsewhere with PostgreSQL's default
 * <em>transactional</em> lock holds a transaction open for the whole run, and the build would wait for it for ever.
 * So the wait is bounded ({@code lock_timeout}, 30 s); past it the half-built index is dropped and built the
 * plain way — writes blocked while it builds, as on any other database, but never a migration that hangs.
 */
public class V56_1__Flow_control_indexes extends BaseJavaMigration {

    static final String[][] INDEXES = {
            {"idx_process_released_business_key", "process_entity (released_business_key)"},
            {"idx_http_task_topic_priority", "http_task (topic, priority, created_at)"},
    };

    /** How long, in seconds, a concurrent build may wait for older transactions before building plainly (30). */
    static final String WAIT_PROPERTY = "eventconductor.migration.concurrent-index-wait-seconds";

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var postgres = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
        for (var index : INDEXES) {
            try (var statement = connection.createStatement()) {
                if (postgres) {
                    buildConcurrently(connection, index[0], index[1]);
                } else {
                    statement.execute("CREATE INDEX IF NOT EXISTS " + index[0] + " ON " + index[1]);
                }
            }
        }
    }

    private static void buildConcurrently(Connection connection, String name, String on) throws SQLException {
        try (var statement = connection.createStatement()) {
            if (invalid(connection, name)) {
                statement.execute("DROP INDEX IF EXISTS " + name);
            }
            statement.execute("SET lock_timeout = '" + Math.max(1, Long.getLong(WAIT_PROPERTY, 30)) + "s'");
            try {
                statement.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name + " ON " + on);
            } catch (SQLException waitedTooLong) {
                // 55P03: lock_not_available — an older transaction would not end. Build it the plain way.
                if (!"55P03".equals(waitedTooLong.getSQLState())) {
                    throw waitedTooLong;
                }
                statement.execute("SET lock_timeout = 0");
                statement.execute("DROP INDEX IF EXISTS " + name);
                statement.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + on);
            } finally {
                statement.execute("SET lock_timeout = 0");
            }
        }
    }

    private static boolean invalid(Connection connection, String name) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT 1 FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid"
                + " WHERE c.relname = ? AND NOT i.indisvalid")) {
            ps.setString(1, name);
            try (var rows = ps.executeQuery()) {
                return rows.next();
            }
        }
    }
}
