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
import io.mateu.workflow.dtos.events.domain.MessageSubscriptionChanged;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The projection that keeps the shared subscription table in step with what is waiting: a subscribe
 * signal writes this shard's row, an unsubscribe removes it, and where there is no shared store
 * (a single-database engine) it does nothing.
 */
class MessageSubscriptionProjectionHandlerTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<MessageSubscriptionRepository> providerOf(MessageSubscriptionRepository store) {
        var provider = (ObjectProvider<MessageSubscriptionRepository>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    @Test
    void reportsItHandlesTheSubscriptionEvent() {
        var handler = new MessageSubscriptionProjectionHandler(providerOf(null), "shard-a");

        assertThat(handler.eventClass()).isEqualTo(MessageSubscriptionChanged.class);
    }

    @Test
    void aSubscribeSignalWritesThisShardsRow() {
        var store = mock(MessageSubscriptionRepository.class);
        var handler = new MessageSubscriptionProjectionHandler(providerOf(store), "shard-a");

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
    void anUnsubscribeSignalRemovesTheRowByStep() {
        var store = mock(MessageSubscriptionRepository.class);
        var handler = new MessageSubscriptionProjectionHandler(providerOf(store), "shard-a");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", false, "p-1"));

        verify(store).unsubscribe("se-1");
        verify(store, never()).subscribe(any());
    }

    @Test
    void aBlankShardIdIsStoredAsNull() {
        // The engine's own single database has no shard id; the row still carries the routing name/key.
        var store = mock(MessageSubscriptionRepository.class);
        var handler = new MessageSubscriptionProjectionHandler(providerOf(store), "  ");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", true, "p-1"));

        var captor = ArgumentCaptor.forClass(MessageSubscription.class);
        verify(store).subscribe(captor.capture());
        assertThat(captor.getValue().shardId()).isNull();
    }

    @Test
    void withoutASharedStoreItIsANoOp() {
        var store = mock(MessageSubscriptionRepository.class);
        var handler = new MessageSubscriptionProjectionHandler(providerOf(null), "shard-a");

        handler.handle(new MessageSubscriptionChanged("se-1", "payment-received", "O-77", true, "p-1"));

        verifyNoInteractions(store);
    }
}
