package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.mateu.workflow.application.out.MessageSubscriptionRepository;
import io.mateu.workflow.application.readmodel.MessageSubscription;
import io.mateu.workflow.application.services.messagerouting.WaitingMessageFilter;
import io.mateu.workflow.dtos.events.domain.MessageSubscriptionChanged;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The projection that keeps receiving-side routing state in step with what is waiting: a subscribe
 * signal writes this shard's row (layer 2) and adds the pair to the local filter (layer 3), an
 * unsubscribe removes the row, and where there is no shared store (a single-database engine) only
 * the filter half runs.
 */
class MessageSubscriptionProjectionHandlerTest {

    private final WaitingMessageFilter filter = mock(WaitingMessageFilter.class);

    @SuppressWarnings("unchecked")
    private ObjectProvider<MessageSubscriptionRepository> providerOf(MessageSubscriptionRepository store) {
        var provider = (ObjectProvider<MessageSubscriptionRepository>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    private MessageSubscriptionProjectionHandler handler(MessageSubscriptionRepository store, String shardId) {
        return new MessageSubscriptionProjectionHandler(providerOf(store), filter, shardId);
    }

    @Test
    void reportsItHandlesTheSubscriptionEvent() {
        assertThat(handler(null, "shard-a").eventClass()).isEqualTo(MessageSubscriptionChanged.class);
    }

    @Test
    void aSubscribeSignalWritesThisShardsRow() {
        var store = mock(MessageSubscriptionRepository.class);
        var handler = handler(store, "shard-a");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", true, "p-1"));

        var captor = ArgumentCaptor.forClass(MessageSubscription.class);
        verify(store).subscribe(captor.capture());
        var row = captor.getValue();
        assertThat(row.stepExecutionId()).isEqualTo("se-1");
        assertThat(row.messageName()).isEqualTo("payment-received");
        assertThat(row.correlationKey()).isEqualTo("O-77");
        assertThat(row.shardId()).isEqualTo("shard-a");
        assertThat(row.updatedAt()).isNotNull();
    }

    @Test
    void aSubscribeSignalAddsThePairToTheLocalFilter() {
        var handler = handler(mock(MessageSubscriptionRepository.class), "shard-a");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", true, "p-1"));

        verify(filter).add("payment-received", "O-77");
    }

    @Test
    void anUnsubscribeSignalRemovesTheRowByStepAndDoesNotTouchTheFilter() {
        var store = mock(MessageSubscriptionRepository.class);
        var handler = handler(store, "shard-a");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", false, "p-1"));

        verify(store).unsubscribe("se-1");
        verify(store, never()).subscribe(any());
        verify(filter, never()).add(any(), any());
    }

    @Test
    void aBlankShardIdIsStoredAsNull() {
        // The engine's own single database has no shard id; the row still carries the routing name/key.
        var store = mock(MessageSubscriptionRepository.class);
        var handler = handler(store, "  ");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", true, "p-1"));

        var captor = ArgumentCaptor.forClass(MessageSubscription.class);
        verify(store).subscribe(captor.capture());
        assertThat(captor.getValue().shardId()).isNull();
    }

    @Test
    void withoutASharedStoreItStillFeedsTheFilter() {
        // No subscription table (single-database engine): the row write is skipped, but the local
        // filter — which needs no shared store — is still fed so layer 3 works.
        var handler = handler(null, "shard-a");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", true, "p-1"));

        verify(filter).add("payment-received", "O-77");
    }

    @Test
    void withoutASharedStoreAnUnsubscribeIsANoOp() {
        var store = mock(MessageSubscriptionRepository.class);
        var handler = handler(null, "shard-a");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", false, "p-1"));

        verifyNoInteractions(store);
        verify(filter, never()).add(any(), any());
    }
}
