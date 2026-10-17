package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.application.out.InvocationRepository;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two retention jobs that are always on: they purge on their interval with the right cutoff, a
 * failing pass does not cancel the schedule, and the receipts one can be switched off with a zero
 * interval.
 */
class RetentionSchedulesTest {

    @Test
    void receiptsArePurgedOnTheIntervalUpToTheRetentionWindowAndAFailureDoesNotStopIt() {
        var receipts = mock(EventReceiptRepository.class);
        when(receipts.purgeReceivedBefore(any()))
                .thenThrow(new IllegalStateException("blip"))
                .thenReturn(3)
                .thenReturn(0);
        var properties = new EventDestinationsProperties();
        properties.getReceipts().setPurgeInterval(Duration.ofMillis(20));
        properties.getReceipts().setRetention(Duration.ofDays(7));
        var retention = new InboundEventReceiptRetention(receipts, properties);
        var before = Instant.now();

        retention.start();
        try {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(receipts, atLeast(3)).purgeReceivedBefore(any()));
        } finally {
            retention.stop();
        }

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(receipts, atLeastOnce()).purgeReceivedBefore(captor.capture());
        // Only what is older than the window.
        assertThat(captor.getValue()).isBefore(before.minus(Duration.ofDays(7)).plusSeconds(10))
                .isAfter(before.minus(Duration.ofDays(7)).minusSeconds(10));
    }

    @Test
    void aZeroIntervalTurnsReceiptRetentionOff() throws Exception {
        var receipts = mock(EventReceiptRepository.class);
        var properties = new EventDestinationsProperties();
        properties.getReceipts().setPurgeInterval(Duration.ZERO);
        var retention = new InboundEventReceiptRetention(receipts, properties);

        retention.start();
        Thread.sleep(100);
        retention.stop();

        verifyNoInteractions(receipts);
    }

    @Test
    void expiredInvocationsArePurgedOnTheIntervalAndAFailureDoesNotStopIt() {
        var invocations = mock(InvocationRepository.class);
        when(invocations.purgeExpired(any()))
                .thenThrow(new IllegalStateException("blip"))
                .thenReturn(2)
                .thenReturn(0);
        var retention = new InvocationRetention(invocations);
        retention.purgeInterval = Duration.ofMillis(20);
        var before = LocalDateTime.now();

        retention.start();
        try {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(invocations, atLeast(3)).purgeExpired(any()));
        } finally {
            retention.stop();
        }

        var captor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(invocations, atLeastOnce()).purgeExpired(captor.capture());
        // The cutoff is "now": an invocation is purged once its own expiry has passed, no earlier.
        assertThat(captor.getValue()).isAfterOrEqualTo(before).isBeforeOrEqualTo(LocalDateTime.now());
    }
}
