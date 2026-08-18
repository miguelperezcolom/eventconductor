package io.mateu.workflow.projector;

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

/**
 * Seeds the read database from a shard's write database — the cutover step, and the one place in this
 * design that needs to know the shard list.
 *
 * <p>The projector alone cannot produce a complete index at cutover: it starts from the topic, and the
 * topic only carries status changes that happened after the shards were switched to remote mode. Every
 * process that already existed would be missing — including in-flight ones, whose absence from the
 * index is what a command router would read as "not placed anywhere".
 *
 * <p>Two tables are seeded, for two different reasons:
 * <ul>
 *   <li>{@code process_index}, so the fleet view is complete from the first query. Rows are written
 *       with the process's own {@code created} as the ordering stamp so that <b>any</b> live event
 *       arriving from the topic supersedes them — a backfill must never win against a real transition,
 *       and the store's staleness guard is what enforces that.</li>
 *   <li>{@code process_placement}, so the ingress router's claim knows where existing business keys
 *       already live. Without it the first redelivery of an old creation is placed afresh, which is
 *       the duplicate the claim exists to prevent.</li>
 * </ul>
 *
 * <p>Idempotent, and safe to run repeatedly and while the fleet is live: index rows lose to anything
 * newer, and placements are claimed rather than overwritten (an existing claim always wins).
 */
public class ShardBackfill {

    private static final Logger log = LoggerFactory.getLogger(ShardBackfill.class);

    /** Top-level processes only: a child lives on its parent's shard and is never placed. */
    private static final String READ_PROCESSES = """
            SELECT id, business_key, workflow_definition_id, workflow_definition_version, status,
                   completion_percentage, created, started, finished
            FROM process_entity
            WHERE parent_step_execution_id IS NULL""";

    private static final String CLAIM_PLACEMENT_POSTGRES = """
            INSERT INTO process_placement (business_key, shard_id, claimed_at) VALUES (?,?,?)
            ON CONFLICT (business_key) DO NOTHING""";

    private final ProcessIndexRepository index;
    private final DataSource readDatabase;

    public ShardBackfill(ProcessIndexRepository index, DataSource readDatabase) {
        this.index = index;
        this.readDatabase = readDatabase;
    }

    /** Copies one shard's processes into the read database. Returns how many rows it seeded. */
    public int backfill(DataSource shard, String shardId) {
        var seeded = 0;
        try (var connection = shard.getConnection();
             var statement = connection.prepareStatement(READ_PROCESSES);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                var row = toRow(rows, shardId);
                index.upsert(row);
                if (row.businessKey() != null && !row.businessKey().isBlank()) {
                    claimPlacement(row.businessKey(), shardId);
                }
                seeded++;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Backfill of shard '" + shardId + "' failed", e);
        }
        log.info("Backfilled {} process(es) from shard {} into the read database", seeded, shardId);
        return seeded;
    }

    private ProcessIndexRow toRow(ResultSet rows, String shardId) throws SQLException {
        return new ProcessIndexRow(
                rows.getString("id"),
                rows.getString("business_key"),
                rows.getString("workflow_definition_id"),
                rows.getInt("workflow_definition_version"),
                rows.getString("status"),
                rows.getInt("completion_percentage"),
                localDateTime(rows, "created"),
                localDateTime(rows, "started"),
                localDateTime(rows, "finished"),
                // The ordering stamp is the process's creation, deliberately the oldest thing we know
                // about it, so every real transition on the topic outranks this row.
                localDateTime(rows, "created"),
                shardId);
    }

    private void claimPlacement(String businessKey, String shardId) throws SQLException {
        try (var connection = readDatabase.getConnection()) {
            if (isPostgres(connection)) {
                try (var statement = connection.prepareStatement(CLAIM_PLACEMENT_POSTGRES)) {
                    bindPlacement(statement, businessKey, shardId);
                    statement.executeUpdate();
                }
                return;
            }
            // Portable path: insert, and let the primary key refuse a key that is already placed.
            try (var statement = connection.prepareStatement(
                    "INSERT INTO process_placement (business_key, shard_id, claimed_at) VALUES (?,?,?)")) {
                bindPlacement(statement, businessKey, shardId);
                statement.executeUpdate();
            } catch (SQLException alreadyPlaced) {
                // An existing claim always wins — it is the authority this table exists to be.
            }
        }
    }

    private static void bindPlacement(PreparedStatement statement, String businessKey, String shardId)
            throws SQLException {
        statement.setString(1, businessKey);
        statement.setString(2, shardId);
        statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    }

    private static LocalDateTime localDateTime(ResultSet rows, String column) throws SQLException {
        var value = rows.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
