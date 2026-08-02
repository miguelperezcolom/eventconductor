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
    void survivesACycleInTheCauseChain() {
        // Java forbids a throwable causing itself, but nothing stops two from causing each other.
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(EventFailures.isRetryable(a)).isFalse();
    }
}
