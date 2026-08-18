package io.mateu.workflow.processindex;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The process-index read store over plain JDBC, on <b>any</b> data source.
 *
 * <p>Plain JDBC and not JPA, and no Spring beyond a {@link DataSource}, because of who uses it: the
 * standalone projector, whose whole point is to be a small service that is not the engine, and the
 * engine's own <b>remote</b> read adapter, which reads a second database that must not become a
 * second {@code EntityManagerFactory}. {@code ProcessIndexDBRepository} remains the adapter for the
 * embedded case, where the index shares the engine's own persistence unit.
 *
 * <p><b>The upsert is atomic on PostgreSQL.</b> One {@code INSERT … ON CONFLICT … DO UPDATE … WHERE}
 * replaces a read-then-write: half the round-trips, and the staleness guard stops depending on the
 * caller being single-threaded per process. It currently is — the topic is keyed by {@code processId},
 * so all of a process's events land on one partition and therefore one consumer thread — but that is
 * a property of how the channel happens to be partitioned today, and a guard that survives changing
 * it costs one statement. Everything else (H2 in the tests) falls back to the guarded read-then-write,
 * which is correct under that same per-process serialisation.
 */
public class JdbcProcessIndexStore implements ProcessIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcProcessIndexStore.class);

    private static final String COLUMNS = """
            process_id, business_key, workflow_definition_id, workflow_definition_version, status,
            completion_percentage, created, started, finished, updated_at, shard_id""";

    /**
     * The staleness guard lives in the {@code WHERE}: an event older than what is stored updates
     * nothing. {@code IS NULL} on either side means "no ordering information", and the incoming row
     * wins — the same as the read-then-write path, which only skips when both stamps are present.
     */
    private static final String POSTGRES_UPSERT = """
            INSERT INTO process_index (%s) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (process_id) DO UPDATE SET
                business_key = EXCLUDED.business_key,
                workflow_definition_id = EXCLUDED.workflow_definition_id,
                workflow_definition_version = EXCLUDED.workflow_definition_version,
                status = EXCLUDED.status,
                completion_percentage = EXCLUDED.completion_percentage,
                created = EXCLUDED.created,
                started = EXCLUDED.started,
                finished = EXCLUDED.finished,
                updated_at = EXCLUDED.updated_at,
                shard_id = EXCLUDED.shard_id
            WHERE process_index.updated_at IS NULL
               OR EXCLUDED.updated_at IS NULL
               OR EXCLUDED.updated_at >= process_index.updated_at""".formatted(COLUMNS);

    private final DataSource dataSource;
    private final boolean atomicUpsert;

    public JdbcProcessIndexStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.atomicUpsert = detectPostgres(dataSource);
    }

    private static boolean detectPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            var product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            var postgres = product.contains("postgres");
            log.info("Process-index store on {}: {} upsert", product,
                    postgres ? "atomic ON CONFLICT" : "guarded read-then-write");
            return postgres;
        } catch (SQLException e) {
            // Not fatal and not worth failing startup over: the fallback is correct everywhere, it
            // just costs a round-trip. The database may simply not be up yet.
            log.warn("Could not identify the read database while choosing the upsert strategy; "
                    + "falling back to the guarded read-then-write ({})", e.getMessage());
            return false;
        }
    }

    @Override
    public void upsert(ProcessIndexRow row) {
        if (atomicUpsert) {
            atomicUpsert(row);
            return;
        }
        guardedUpsert(row);
    }

    private void atomicUpsert(ProcessIndexRow row) {
        withConnection(connection -> {
            try (var statement = connection.prepareStatement(POSTGRES_UPSERT)) {
                bindRow(statement, row);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Read the stored stamp, skip an older event, otherwise delete-and-insert. */
    private void guardedUpsert(ProcessIndexRow row) {
        withConnection(connection -> {
            var stored = queryOne(connection,
                    "SELECT updated_at FROM process_index WHERE process_id = ?",
                    statement -> statement.setString(1, row.processId()),
                    rows -> timestamp(rows, "updated_at"));
            if (stored.isPresent() && row.updatedAt() != null && row.updatedAt().isBefore(stored.get())) {
                return null;
            }
            try (var delete = connection.prepareStatement("DELETE FROM process_index WHERE process_id = ?")) {
                delete.setString(1, row.processId());
                delete.executeUpdate();
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO process_index (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                bindRow(insert, row);
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ProcessIndexRow> findByStatusIn(Collection<String> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        var list = List.copyOf(statuses);
        return query("SELECT " + COLUMNS + " FROM process_index WHERE status IN (" + placeholders(list.size()) + ")",
                statement -> bindAll(statement, 1, list));
    }

    @Override
    public List<ProcessIndexRow> findByWorkflowDefinitionIdAndStatusIn(String workflowDefinitionId,
                                                                      Collection<String> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        var list = List.copyOf(statuses);
        return query("SELECT " + COLUMNS + " FROM process_index WHERE workflow_definition_id = ? "
                        + "AND status IN (" + placeholders(list.size()) + ")",
                statement -> {
                    statement.setString(1, workflowDefinitionId);
                    bindAll(statement, 2, list);
                });
    }

    @Override
    public Optional<ProcessIndexRow> findByBusinessKey(String businessKey) {
        return query("SELECT " + COLUMNS + " FROM process_index WHERE business_key = ?",
                statement -> statement.setString(1, businessKey)).stream().findFirst();
    }

    @Override
    public Optional<ProcessIndexRow> findByProcessId(String processId) {
        return query("SELECT " + COLUMNS + " FROM process_index WHERE process_id = ?",
                statement -> statement.setString(1, processId)).stream().findFirst();
    }

    @Override
    public Map<String, Long> countByStatus() {
        return withConnection(connection -> {
            var counts = new LinkedHashMap<String, Long>();
            try (var statement = connection.prepareStatement(
                    "SELECT status, count(*) FROM process_index GROUP BY status");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    counts.put(rows.getString(1), rows.getLong(2));
                }
            }
            return counts;
        });
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private List<ProcessIndexRow> query(String sql, Binder binder) {
        return withConnection(connection -> {
            var found = new ArrayList<ProcessIndexRow>();
            try (var statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        found.add(readRow(rows));
                    }
                }
            }
            return List.copyOf(found);
        });
    }

    private <T> Optional<T> queryOne(Connection connection, String sql, Binder binder, RowReader<T> reader)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(reader.read(rows)) : Optional.empty();
            }
        }
    }

    private <T> T withConnection(ConnectionCallback<T> callback) {
        try (var connection = dataSource.getConnection()) {
            return callback.call(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Process-index read store failed", e);
        }
    }

    private static void bindRow(PreparedStatement statement, ProcessIndexRow row) throws SQLException {
        statement.setString(1, row.processId());
        statement.setString(2, row.businessKey());
        statement.setString(3, row.workflowDefinitionId());
        statement.setInt(4, row.workflowDefinitionVersion());
        statement.setString(5, row.status());
        statement.setInt(6, row.completionPercentage());
        statement.setTimestamp(7, timestamp(row.created()));
        statement.setTimestamp(8, timestamp(row.started()));
        statement.setTimestamp(9, timestamp(row.finished()));
        statement.setTimestamp(10, timestamp(row.updatedAt()));
        statement.setString(11, row.shardId());
    }

    private static ProcessIndexRow readRow(ResultSet rows) throws SQLException {
        return new ProcessIndexRow(
                rows.getString("process_id"),
                rows.getString("business_key"),
                rows.getString("workflow_definition_id"),
                rows.getInt("workflow_definition_version"),
                rows.getString("status"),
                rows.getInt("completion_percentage"),
                timestamp(rows, "created"),
                timestamp(rows, "started"),
                timestamp(rows, "finished"),
                timestamp(rows, "updated_at"),
                rows.getString("shard_id"));
    }

    private static void bindAll(PreparedStatement statement, int from, List<String> values) throws SQLException {
        for (var i = 0; i < values.size(); i++) {
            statement.setString(from + i, values.get(i));
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime timestamp(ResultSet rows, String column) throws SQLException {
        var value = rows.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rows) throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionCallback<T> {
        T call(Connection connection) throws SQLException;
    }
}
