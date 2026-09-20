package io.mateu.workflow.processindex;

import io.mateu.workflow.application.out.MessageSubscriptionRepository;
import io.mateu.workflow.application.readmodel.MessageSubscription;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The message-subscription routing store over plain JDBC, on any data source — the same shape as
 * {@link JdbcProcessIndexStore}, and for the same reasons (the standalone projector and the engine's
 * remote read adapter both use it, neither wanting a second JPA unit).
 *
 * <p>Keyed by step execution, so a subscribe is an idempotent upsert and an unsubscribe a delete by
 * that id. On PostgreSQL the upsert is one {@code INSERT … ON CONFLICT … DO UPDATE}; elsewhere (H2 in
 * the tests) it is a guarded update-then-insert, correct under the per-step serialisation the
 * projection channel gives (keyed by process/step, one consumer thread).
 */
public class JdbcMessageSubscriptionStore implements MessageSubscriptionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcMessageSubscriptionStore.class);

    private static final String POSTGRES_UPSERT = """
            INSERT INTO message_subscription
                (step_execution_id, message_name, correlation_key, shard_id, updated_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT (step_execution_id) DO UPDATE SET
                message_name = EXCLUDED.message_name,
                correlation_key = EXCLUDED.correlation_key,
                shard_id = EXCLUDED.shard_id,
                updated_at = EXCLUDED.updated_at""";

    private static final String UPDATE = """
            UPDATE message_subscription SET message_name = ?, correlation_key = ?, shard_id = ?,
                updated_at = ? WHERE step_execution_id = ?""";

    private static final String INSERT = """
            INSERT INTO message_subscription
                (step_execution_id, message_name, correlation_key, shard_id, updated_at)
            VALUES (?,?,?,?,?)""";

    private static final String DELETE = "DELETE FROM message_subscription WHERE step_execution_id = ?";

    private static final String SELECT_SHARDS = """
            SELECT DISTINCT shard_id FROM message_subscription
            WHERE message_name = ? AND correlation_key = ? AND shard_id IS NOT NULL""";

    private final DataSource dataSource;
    private final boolean postgres;

    public JdbcMessageSubscriptionStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.postgres = isPostgres(dataSource);
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Could not identify the subscription database; using the portable upsert ({})",
                    e.getMessage());
            return false;
        }
    }

    @Override
    public void subscribe(MessageSubscription subscription) {
        try (var connection = dataSource.getConnection()) {
            var now = Timestamp.valueOf(subscription.updatedAt());
            if (postgres) {
                try (var statement = connection.prepareStatement(POSTGRES_UPSERT)) {
                    statement.setString(1, subscription.stepExecutionId());
                    statement.setString(2, subscription.messageName());
                    statement.setString(3, subscription.correlationKey());
                    statement.setString(4, subscription.shardId());
                    statement.setTimestamp(5, now);
                    statement.executeUpdate();
                }
                return;
            }
            try (var update = connection.prepareStatement(UPDATE)) {
                update.setString(1, subscription.messageName());
                update.setString(2, subscription.correlationKey());
                update.setString(3, subscription.shardId());
                update.setTimestamp(4, now);
                update.setString(5, subscription.stepExecutionId());
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (var insert = connection.prepareStatement(INSERT)) {
                insert.setString(1, subscription.stepExecutionId());
                insert.setString(2, subscription.messageName());
                insert.setString(3, subscription.correlationKey());
                insert.setString(4, subscription.shardId());
                insert.setTimestamp(5, now);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not record message subscription for step " + subscription.stepExecutionId(), e);
        }
    }

    @Override
    public void unsubscribe(String stepExecutionId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, stepExecutionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not remove message subscription for step " + stepExecutionId, e);
        }
    }

    @Override
    public List<String> shardsWaitingFor(String messageName, String correlationKey) {
        if (messageName == null || correlationKey == null) {
            return List.of();
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SELECT_SHARDS)) {
            statement.setString(1, messageName);
            statement.setString(2, correlationKey);
            try (var rows = statement.executeQuery()) {
                var shards = new ArrayList<String>();
                while (rows.next()) {
                    shards.add(rows.getString(1));
                }
                return shards;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read message subscriptions for '"
                    + messageName + "'/'" + correlationKey + "'", e);
        }
    }
}
