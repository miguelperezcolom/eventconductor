package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

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
        var depth = 0;
        for (var cause = failure; cause != null && depth++ < MAX_CAUSE_DEPTH; cause = cause.getCause()) {
            if (cause instanceof ConcurrentProcessAccessException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    // The database being unreachable is the plainest retryable failure there is,
                    // and Spring files both of these outside the transient family.
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }

    private EventFailures() {
    }
}
