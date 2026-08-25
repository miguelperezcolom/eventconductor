package io.mateu.workflow.processindex;

import io.mateu.workflow.application.out.ProcessPlacementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * The placement claim over JDBC.
 *
 * <p>On PostgreSQL the whole thing is one statement:
 *
 * <pre>
 * INSERT INTO process_placement (business_key, shard_id, claimed_at) VALUES (?,?,?)
 * ON CONFLICT (business_key) DO UPDATE SET business_key = process_placement.business_key
 * RETURNING shard_id
 * </pre>
 *
 * <p>The {@code DO UPDATE} is a no-op that exists purely so {@code RETURNING} yields a row on
 * conflict — {@code DO NOTHING} returns nothing at all, which would force a second query and put a
 * race back between the two. One round-trip, and the winner and every loser read the same answer.
 *
 * <p>Elsewhere (H2, in the tests) the same contract is met by insert-then-read-on-conflict: the
 * primary key is what actually decides the winner in both cases, so the only difference is how many
 * round-trips it takes to find out.
 *
 * <p><b>Nothing here is caught.</b> A claim that cannot be made must fail the creation — see
 * {@link ProcessPlacementRepository}.
 */
public class JdbcProcessPlacementStore implements ProcessPlacementRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcProcessPlacementStore.class);

    private static final String POSTGRES_CLAIM = """
            INSERT INTO process_placement (business_key, shard_id, claimed_at) VALUES (?,?,?)
            ON CONFLICT (business_key) DO UPDATE SET business_key = process_placement.business_key
            RETURNING shard_id""";

    private static final String INSERT = """
            INSERT INTO process_placement (business_key, shard_id, claimed_at) VALUES (?,?,?)""";

    private static final String SELECT =
            "SELECT shard_id FROM process_placement WHERE business_key = ?";

    private final DataSource dataSource;
    private final boolean singleStatementClaim;

    public JdbcProcessPlacementStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.singleStatementClaim = isPostgres(dataSource);
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Could not identify the placement database; using the portable two-statement "
                    + "claim ({})", e.getMessage());
            return false;
        }
    }

    @Override
    public String claim(String businessKey, String candidateShardId) {
        try (var connection = dataSource.getConnection()) {
            if (singleStatementClaim) {
                try (var statement = connection.prepareStatement(POSTGRES_CLAIM)) {
                    statement.setString(1, businessKey);
                    statement.setString(2, candidateShardId);
                    statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) {
                            return rows.getString(1);
                        }
                    }
                }
                // Unreachable with the no-op DO UPDATE above; if it ever is reached, the incumbent
                // is still the right answer and reading it is better than inventing one.
                return read(connection, businessKey, candidateShardId);
            }
            try (var statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, businessKey);
                statement.setString(2, candidateShardId);
                statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                statement.executeUpdate();
                return candidateShardId;
            } catch (SQLException duplicateKey) {
                // Somebody claimed it first — theirs is the placement, and it is not negotiable.
                return read(connection, businessKey, candidateShardId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not claim a shard for business key '" + businessKey + "'", e);
        }
    }

    private String read(java.sql.Connection connection, String businessKey, String fallback)
            throws SQLException {
        try (var statement = connection.prepareStatement(SELECT)) {
            statement.setString(1, businessKey);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : fallback;
            }
        }
    }
}
