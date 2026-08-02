package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

class EventFailuresTest {

    @Test
    void lostRacesAreWorthRetrying() {
        assertThat(EventFailures.isRetryable(
                new ConcurrentProcessAccessException("p-1", new RuntimeException()))).isTrue();
    }

    @Test
    void environmentFailuresAreWorthRetrying() {
        assertThat(EventFailures.isRetryable(new CannotAcquireLockException("busy"))).isTrue();
        assertThat(EventFailures.isRetryable(new QueryTimeoutException("slow"))).isTrue();
        assertThat(EventFailures.isRetryable(new DataAccessResourceFailureException("db down"))).isTrue();
        assertThat(EventFailures.isRetryable(new CannotCreateTransactionException("no connection"))).isTrue();
    }

    @Test
    void aDefectiveEventIsNot() {
        // A report for a step execution that no longer exists will fail the same way forever.
        assertThat(EventFailures.isRetryable(new java.util.NoSuchElementException("No value present")))
                .isFalse();
        assertThat(EventFailures.isRetryable(new IllegalArgumentException("bad payload"))).isFalse();
        assertThat(EventFailures.isRetryable(new NullPointerException())).isFalse();
    }

    @Test
    void looksThroughTheCauseChain() {
        // Handlers wrap; the classification must not depend on what is on top.
        assertThat(EventFailures.isRetryable(
                new IllegalStateException("while handling", new CannotAcquireLockException("busy"))))
                .isTrue();
    }

    @Test
    void aRollbackThatFailedBecauseTheDatabaseWentAwayIsWorthRetrying() {
        // The exact shape observed when PostgreSQL was stopped mid-transaction: Spring reports a
        // JpaSystemException that its translator files as neither transient nor a resource
        // failure, and two process creations were dead-lettered for it. SQLState class 08 is
        // "connection exception" — the database was simply gone.
        var connectionGone = new java.sql.SQLException("This connection has been closed.", "08003");

        assertThat(EventFailures.isRetryable(new org.springframework.orm.jpa.JpaSystemException(
                new RuntimeException("Unable to rollback against JDBC Connection", connectionGone))))
                .isTrue();
    }

    @Test
    void recognisesConnectionFailuresByStateAndByType() {
        assertThat(EventFailures.isRetryable(
                new java.sql.SQLException("terminating connection due to administrator command", "57P01")))
                .isTrue();
        assertThat(EventFailures.isRetryable(
                new java.sql.SQLException("out of memory", "53200"))).isTrue();
        // Drivers that set no state at all still say it in the type.
        assertThat(EventFailures.isRetryable(
                new java.sql.SQLRecoverableException("socket closed"))).isTrue();
        assertThat(EventFailures.isRetryable(
                new java.sql.SQLNonTransientConnectionException("connection refused"))).isTrue();
    }

    @Test
    void aStatementLevelSqlErrorIsStillDefective() {
        // Class 23 is integrity constraint violation: retrying it forever is a poison pill, and
        // widening the connection check must not swallow it.
        assertThat(EventFailures.isRetryable(
                new java.sql.SQLException("duplicate key value violates unique constraint", "23505")))
                .isFalse();
        assertThat(EventFailures.isRetryable(
                new java.sql.SQLException("syntax error", "42601"))).isFalse();
        assertThat(EventFailures.isRetryable(new java.sql.SQLException("no state at all"))).isFalse();
    }

    @Test
    void survivesACycleInTheCauseChain() {
        // Java forbids a throwable causing itself, but nothing stops two from causing each other.
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(EventFailures.isRetryable(a)).isFalse();
    }
}
