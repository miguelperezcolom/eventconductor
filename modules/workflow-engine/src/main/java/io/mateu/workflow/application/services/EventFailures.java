package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.PoisonEventException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Whether a failed event is worth trying again, or is never going to succeed.
 *
 * <p>The engine keeps retrying the first kind forever and parks the second kind at once. That is
 * a deliberate choice over the usual "retry N times, then give up": N attempts is a guess about
 * how long an outage lasts, and getting it wrong drops events that would have worked. If a
 * failure can succeed later, time is the only thing it needs; if it cannot, no number of attempts
 * changes that, and repeating it just buries the log.
 *
 * <p>So the classification carries the weight, and it is deliberately narrow. Only failures
 * <em>known</em> to be about the environment — the database unreachable, a lock not obtained in
 * time, a lost race — count as retryable. Everything else is treated as defective and parked
 * where it can be looked at, which is the safer way to be wrong: a parked event can be replayed
 * once someone understands it, while an event retried forever is an infinite loop nobody reads.
 */
public final class EventFailures {

    /** Cause chains are shallow; a bound is cheaper than tracking visits, and cycles are real. */
    private static final int MAX_CAUSE_DEPTH = 20;

    public static boolean isRetryable(Throwable failure) {
        // Poison beats retryable. A defective event — one that names a step, process or definition
        // that does not exist — stays parked even if it also surfaced a retryable-looking failure
        // on the way (a data-access exception raised while loading the row that is not there).
        // Checked first, and across the whole chain, so the incidental exception cannot flip a
        // defective event into one retried forever.
        var depth = 0;
        for (var cause = failure; cause != null && depth++ < MAX_CAUSE_DEPTH; cause = cause.getCause()) {
            if (cause instanceof PoisonEventException) {
                return false;
            }
        }
        depth = 0;
        for (var cause = failure; cause != null && depth++ < MAX_CAUSE_DEPTH; cause = cause.getCause()) {
            if (cause instanceof ConcurrentProcessAccessException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    // The database being unreachable is the plainest retryable failure there is,
                    // and Spring files both of these outside the transient family.
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException
                    // A full embedded worker pool. Nothing is wrong with the message: there is
                    // no room for it this instant, and the outbox is the one place that can hold
                    // it until there is. Parking it would dead-letter a queue being busy.
                    || cause instanceof RejectedExecutionException) {
                return true;
            }
            if (cause instanceof SQLException sql && isConnectionFailure(sql)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a {@link SQLException} says the connection failed rather than the statement.
     *
     * <p>Spring's exception hierarchy does not always reach these. Stopping the database mid-flight
     * produced {@code JpaSystemException: Unable to rollback against JDBC Connection}, which is
     * neither transient nor a resource failure as far as the translator is concerned, so two
     * process creations were dead-lettered during a ninety-second outage — parked as defective
     * when the only thing wrong was that the database had gone away. Failing to roll back because
     * the connection is gone is the single most retryable thing that can happen to this engine.
     *
     * <p>Matched on SQLState rather than on type, because drivers are inconsistent about which
     * {@code SQLException} subclass they throw but consistent about the state: class {@code 08} is
     * "connection exception" in the SQL standard, class {@code 53} is "insufficient resources",
     * and {@code 57P01}-{@code 57P03} are PostgreSQL shutting down, crashing and refusing
     * connections. The subclass checks stay as a second net for drivers that set no state at all.
     */
    private static boolean isConnectionFailure(SQLException sql) {
        if (sql instanceof SQLTransientException
                || sql instanceof SQLRecoverableException
                || sql instanceof SQLNonTransientConnectionException) {
            return true;
        }
        var state = sql.getSQLState();
        if (state == null || state.length() < 2) {
            return false;
        }
        return state.startsWith("08")
                || state.startsWith("53")
                || state.equals("57P01") || state.equals("57P02") || state.equals("57P03");
    }

    private EventFailures() {
    }
}
