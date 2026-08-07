package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The batch-transaction fast path: a slice of a poll batch tried as one transaction, falling back
 * to one per process when it does not commit.
 *
 * <p>Every case here is about the same claim — that the fast path has two outcomes and not three.
 * It commits the whole slice, or it commits nothing and the original path runs from the state it
 * would have run from anyway. A test that only showed the happy path would be showing the half that
 * cannot go wrong.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestratorBatchTransactionTest {

    @Mock ProcessDomainEventUseCase processDomainEventUseCase;
    @Mock ProcessUpstreamEventUseCase processUpstreamEventUseCase;
    @Mock TransactionTemplate transactionTemplate;
    @Mock DeadLetterPublisher deadLetterPublisher;

    private final List<String> handled = new ArrayList<>();
    private OrchestratorKafkaConsumerConfig config;

    @BeforeEach
    void setUp() {
        // The transaction is the unit of the guarantee, not of this test: run the callback inline
        // and let exceptions out, so what is asserted is what the callback did.
        doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        config = new OrchestratorKafkaConsumerConfig(processDomainEventUseCase,
                processUpstreamEventUseCase, transactionTemplate, deadLetterPublisher);
        config.maxProcessesPerTransaction = 32;
        config.backoffSlices = 20;
    }

    /** Records every event that reached a handler, and optionally fails one process. */
    private void handlerFailing(String processId, RuntimeException failure) {
        doAnswer(invocation -> {
            var event = invocation.getArgument(0,
                    io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand.class);
            var id = ((ProcessCreated) event.event()).processId();
            handled.add(id);
            if (id.equals(processId)) {
                throw failure;
            }
            return null;
        }).when(processUpstreamEventUseCase).handle(any());
    }

    private void deliver(String... processIds) {
        List<DomainEvent> events = new ArrayList<>();
        for (var id : processIds) {
            events.add(new ProcessCreated(id, List.of()));
        }
        config.consumeUpstream().accept(events);
    }

    @Test
    void offByDefaultItIsOneTransactionPerProcess() {
        handlerFailing(null, null);

        deliver("p-1", "p-2", "p-3");

        verify(transactionTemplate, times(3)).executeWithoutResult(any());
        assertThat(handled).containsExactly("p-1", "p-2", "p-3");
    }

    @Test
    void onAndHealthyItIsOneTransactionForTheWholeSlice() {
        config.batchTransaction = true;
        handlerFailing(null, null);

        deliver("p-1", "p-2", "p-3");

        verify(transactionTemplate, times(1)).executeWithoutResult(any());
        assertThat(handled).containsExactly("p-1", "p-2", "p-3");
    }

    @Test
    void aSingleProcessNeverUsesTheFastPathBecauseThereIsNothingToWin() {
        config.batchTransaction = true;
        handlerFailing(null, null);

        deliver("p-1");

        verify(transactionTemplate, times(1)).executeWithoutResult(any());
    }

    @Test
    void whenOneProcessFailsEveryOtherStillCommitsThroughTheOriginalPath() {
        // The case the whole design exists for. The slice rolls back as a unit, so nothing is
        // half-applied, and the fallback then does exactly what it does today: park the poisoned
        // process and let the rest through.
        config.batchTransaction = true;
        handlerFailing("bad", new IllegalStateException("this event can never work"));

        deliver("p-1", "bad", "p-3");

        // One failed attempt at the slice, then one transaction per process.
        verify(transactionTemplate, times(4)).executeWithoutResult(any());
        verify(deadLetterPublisher, times(1)).park(any(), any(), any());
        // p-1 ran twice: once in the attempt that rolled back, once in the fallback. That is the
        // cost of the fast path, and it is why it relies on handlers being idempotent — the same
        // property the retryable path already relies on.
        assertThat(handled).containsExactly("p-1", "bad", "p-1", "bad", "p-3");
    }

    @Test
    void aRetryableFailureStillEscapesSoTheBinderRedeliversTheBatch() {
        // The fast path must not swallow the distinction between "this event is broken" and "the
        // database is having a moment". It classifies nothing; the fallback decides, as before.
        config.batchTransaction = true;
        handlerFailing("flaky", new ConcurrencyFailureException("optimistic conflict"));

        assertThatThrownBy(() -> deliver("p-1", "flaky"))
                .isInstanceOf(ConcurrencyFailureException.class);

        verify(deadLetterPublisher, never()).park(any(), any(), any());
    }

    @Test
    void afterAFailureItStopsTryingForAWhile() {
        // A permanently poisoned partition would otherwise pay for the attempt on every batch and
        // never gain anything.
        config.batchTransaction = true;
        config.backoffSlices = 2;
        handlerFailing("bad", new IllegalStateException("poison"));

        deliver("p-1", "bad");           // attempt (1) + fallback (2) = 3
        org.mockito.Mockito.clearInvocations(transactionTemplate);

        handlerFailing(null, null);
        deliver("p-1", "p-2", "p-3");     // paused: 3 transactions, no attempt
        verify(transactionTemplate, times(3)).executeWithoutResult(any());

        org.mockito.Mockito.clearInvocations(transactionTemplate);
        deliver("p-1", "p-2", "p-3");     // still paused: 3
        verify(transactionTemplate, times(3)).executeWithoutResult(any());

        org.mockito.Mockito.clearInvocations(transactionTemplate);
        deliver("p-1", "p-2", "p-3");     // back on: 1
        verify(transactionTemplate, times(1)).executeWithoutResult(any());
    }

    @Test
    void aBatchIsSlicedSoOneTransactionNeverCoversTheWholeWorld() {
        // The transaction holds its rows for as long as it runs and throws away everything it did
        // on a failure, so its size is a bound on both.
        config.batchTransaction = true;
        config.maxProcessesPerTransaction = 2;
        handlerFailing(null, null);

        deliver("p-1", "p-2", "p-3", "p-4", "p-5");

        // Slices of 2, 2 and 1 — and the last, being a single process, goes straight through.
        verify(transactionTemplate, times(3)).executeWithoutResult(any());
    }
}
