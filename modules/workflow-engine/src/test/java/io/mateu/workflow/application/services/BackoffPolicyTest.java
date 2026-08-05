package io.mateu.workflow.application.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {

    @Test
    void firstRetryWaitsTheBaseDelay() {
        var policy = new BackoffPolicy(1000, 2.0, 60000, 0.0);

        assertThat(policy.nextDelay(1).toMillis()).isEqualTo(1000);
    }

    @Test
    void delayGrowsExponentially() {
        var policy = new BackoffPolicy(1000, 2.0, 60000, 0.0);

        assertThat(policy.nextDelay(1).toMillis()).isEqualTo(1000);
        assertThat(policy.nextDelay(2).toMillis()).isEqualTo(2000);
        assertThat(policy.nextDelay(3).toMillis()).isEqualTo(4000);
        assertThat(policy.nextDelay(4).toMillis()).isEqualTo(8000);
    }

    @Test
    void delayIsCappedAtMax() {
        var policy = new BackoffPolicy(1000, 2.0, 5000, 0.0);

        assertThat(policy.nextDelay(10).toMillis()).isEqualTo(5000);
    }

    @Test
    void largeAttemptCountDoesNotOverflow() {
        var policy = new BackoffPolicy(1000, 2.0, 60000, 0.0);

        // Math.pow(2, 1000) is Infinity — must still cap cleanly, never a negative or absurd delay.
        assertThat(policy.nextDelay(1000).toMillis()).isEqualTo(60000);
    }

    @Test
    void jitterStaysWithinBounds() {
        var policy = new BackoffPolicy(1000, 1.0, 60000, 0.2);

        for (int i = 0; i < 200; i++) {
            long millis = policy.nextDelay(1).toMillis();
            assertThat(millis).isBetween(800L, 1200L);
        }
    }

    @Test
    void neverReturnsNegative() {
        var policy = new BackoffPolicy(0, 2.0, 0, 0.5);

        assertThat(policy.nextDelay(1).toMillis()).isGreaterThanOrEqualTo(0);
        assertThat(policy.nextDelay(5).toMillis()).isGreaterThanOrEqualTo(0);
    }
}
