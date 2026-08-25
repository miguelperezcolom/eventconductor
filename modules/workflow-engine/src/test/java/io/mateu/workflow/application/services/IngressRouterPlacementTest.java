package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.application.out.ShardRegistry;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Placement with a claim store — the shape a sharded fleet should actually run in.
 *
 * <p>The claim exists because the read model cannot answer rule 1 safely: it is eventually consistent,
 * so a creation redelivered before the projection catches up gets round-robined a second time and the
 * fleet ends up with two processes for one business key, on two shards, each running its own side
 * effects. These pin that down: the claim is consulted instead of the index, it survives a different
 * candidate, and a claim that cannot be made stops the creation rather than guessing.
 */
@ExtendWith(MockitoExtension.class)
class IngressRouterPlacementTest {

    @Mock UpstreamEventPublisher upstreamEventPublisher;
    @Mock IngressPublisher ingressPublisher;
    @Mock ProcessIndexRepository processIndexRepository;
    @Mock ShardRegistry shardRegistry;

    /** A stand-in for the real store: first claim wins, everyone else reads the incumbent. */
    private final Map<String, String> placed = new HashMap<>();
    private final ProcessPlacementRepository placement =
            (businessKey, candidate) -> placed.computeIfAbsent(businessKey, k -> candidate);

    private IngressRouter router(ProcessPlacementRepository store) {
        return new IngressRouter(upstreamEventPublisher, ingressPublisher,
                processIndexRepository, shardRegistry,
                IngressRouterTest.placementStore(store), true);
    }

    private ProcessCreationRequested creation(String businessKey) {
        return new ProcessCreationRequested("wd-1", businessKey, List.of());
    }

    @Test
    void newKeysAreSpreadRoundRobinAndTheClaimRecordsWhereEachWent() {
        when(shardRegistry.activeShards()).thenReturn(List.of("s0", "s1"));
        var router = router(placement);

        router.route(creation("k1"));
        router.route(creation("k2"));
        router.route(creation("k3"));

        verify(ingressPublisher).publishToShard(creation("k1"), "s0");
        verify(ingressPublisher).publishToShard(creation("k2"), "s1");
        verify(ingressPublisher).publishToShard(creation("k3"), "s0");
        assertThat(placed).containsExactlyInAnyOrderEntriesOf(Map.of("k1", "s0", "k2", "s1", "k3", "s0"));
    }

    /**
     * The gap this whole mechanism exists to close, in one test: the same creation routed twice, from
     * routers that round-robin to different candidates, must reach one shard — not two.
     */
    @Test
    void theSameKeyRoutedTwiceFromTwoRoutersLandsOnOneShard() {
        when(shardRegistry.activeShards()).thenReturn(List.of("s0", "s1"));
        var one = router(placement);
        var other = router(placement);

        // Skew the second router's cursor so its candidate for the redelivery is the other shard.
        other.route(creation("filler"));

        one.route(creation("order-4711"));
        other.route(creation("order-4711"));

        verify(ingressPublisher, org.mockito.Mockito.times(2))
                .publishToShard(creation("order-4711"), "s0");
        verify(ingressPublisher, never()).publishToShard(creation("order-4711"), "s1");
    }

    @Test
    void theClaimReplacesTheReadModelLookupEntirely() {
        when(shardRegistry.activeShards()).thenReturn(List.of("s0"));

        router(placement).route(creation("k1"));

        // The index is eventually consistent, so consulting it here would be the bug, not a fallback.
        verifyNoInteractions(processIndexRepository);
    }

    @Test
    void aClaimThatCannotBeMadeStopsTheCreationRatherThanPlacingItAnyway() {
        when(shardRegistry.activeShards()).thenReturn(List.of("s0"));
        ProcessPlacementRepository unreachable = (businessKey, candidate) -> {
            throw new IllegalStateException("read database is down");
        };

        // Fail closed: a creation is retryable at its source, a duplicated process is not repairable.
        assertThatThrownBy(() -> router(unreachable).route(creation("k1")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ingressPublisher);
        verifyNoInteractions(upstreamEventPublisher);
    }

    @Test
    void withNoActiveShardNothingIsClaimedAndTheProcessIsCreatedLocally() {
        when(shardRegistry.activeShards()).thenReturn(List.of());
        var creation = creation("k1");

        router(placement).route(creation);

        // Claiming here would pin the key for good to a shard that is not accepting work.
        assertThat(placed).isEmpty();
        verify(upstreamEventPublisher).publish(creation);
        verifyNoInteractions(ingressPublisher);
    }

    @Test
    void aChildProcessIsNeverPlacedAndStaysOnItsParentsShard() {
        lenient().when(shardRegistry.activeShards()).thenReturn(List.of("s0", "s1"));
        var child = new ProcessCreationRequested("wd-child", "parent:se-1", List.of(), "se-1");

        router(placement).route(child);

        assertThat(placed).isEmpty();
        verify(upstreamEventPublisher).publish(child);
        verify(ingressPublisher, never()).publishToShard(any(), any());
    }
}
