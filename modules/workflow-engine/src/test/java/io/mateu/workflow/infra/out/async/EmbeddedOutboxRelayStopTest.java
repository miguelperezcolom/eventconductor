package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The embedded relay's thread ends with its context. It was a {@code while (true)}: closing the
 * context left it polling a closed pool and logging an error every interval for the life of the
 * JVM, which every tool that boots more than one engine in a JVM (the benchmark suite, tests that
 * restart an engine) tripped over.
 */
class EmbeddedOutboxRelayStopTest {

    @Test
    void theRelayThreadEndsWhenTheContextCloses() throws Exception {
        var relay = new EmbeddedOutboxRelay(mock(OutboxMessageEntityRepository.class),
                mock(ProcessDomainEventUseCase.class), mock(JdbcTemplate.class), mock(DbLockDialect.class),
                new OutboxSignal());
        relay.pollIntervalMs = 60_000; // parked in the wait between passes, as an idle relay is
        relay.iterate();
        var thread = relay.thread;
        assertThat(thread.isAlive()).isTrue();

        relay.stop();

        thread.join(5_000);
        assertThat(thread.isAlive()).as("relay thread still running after stop()").isFalse();
    }
}
